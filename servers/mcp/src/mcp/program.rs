// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Programs (Latch Turbo phase 2, R70, ADR-072): the AI thinks once per goal
//! and sends a whole program (open an app, find, tap, type, toggle, read a
//! value, branch, loop), which runs in one call and comes back only when it
//! is done or a step fails in a way the run could not recover from.
//!
//! Elements are named by selectors (words, role, the card they sit in, a
//! container, a position), never by ids or coordinates. When two elements fit
//! a selector equally well the step fails and lists both instead of guessing.
//! A step that finds nothing first tries, in order: reading the screen again,
//! dismissing a known pop-up, scrolling to it, and one more look.
//!
//! Every phone action runs through the ordinary tools, so policy, approvals,
//! freshness, and redaction apply exactly as if the AI had called them one by
//! one. The pure parts (validation, selectors, conditions) are mirrored in
//! `servers/vercel/src/program.ts`; both pass the shared cases in
//! `packages/schemas/v1/program/cases.json`.

use std::sync::Arc;
use std::time::Instant;

use latch_protocol::{ErrorCode, Observation, ProtocolError, UiNode};
use serde_json::{Map, Value, json};

use super::skills::{icon_class, label_parts, match_level, norm, usable};
use super::{device_id_schema, quote, text_result, tool};
use crate::AppState;

pub const NAME: &str = "run_program";

const MAX_TOP_STEPS: usize = 50;
const MAX_TOTAL_STEPS: usize = 120;
const MAX_DEPTH: usize = 3;
const MAX_ITEMS: usize = 30;
const MAX_REPEAT: i64 = 50;
const MAX_STR: usize = 200;
const MAX_CANDIDATES: usize = 5;
/// Phone actions one call performs before pausing (the AI continues with from_step).
const MAX_ACTIONS: usize = 80;
const RUN_BUDGET_MS: u128 = 240_000;

const KINDS: &[&str] = &[
    "open",
    "go",
    "tap",
    "type",
    "find",
    "toggle",
    "scroll",
    "back",
    "home",
    "wait",
    "expect",
    "read",
    "if",
    "for_each",
    "call",
    "ask_owner",
];

const ROLES: &[&str] = &[
    "button", "input", "row", "card", "switch", "tab", "video", "image", "plus", "minus", "send",
    "back", "search", "close", "cart", "heart", "share", "menu",
];

/// Routes a `call` step can name (R71): the phone's own controls, no screen needed.
pub const ROUTES: &[&str] = &[
    "media",
    "volume",
    "flashlight",
    "brightness",
    "ringer",
    "do_not_disturb",
    "panel",
    "link",
    "clipboard",
    "timer",
    "alarm",
];

/// Words of the buttons that close a pop-up without choosing anything (rating
/// prompts, update nags, promo sheets). Never "Cancel" or "OK": those can undo
/// or confirm what the owner was doing.
const POPUP_WORDS: &[&str] = &[
    "not now",
    "no thanks",
    "no, thanks",
    "maybe later",
    "later",
    "skip",
    "dismiss",
    "close",
    "got it",
    "remind me later",
];

fn kind_fields(kind: &str) -> &'static [&'static str] {
    match kind {
        "open" => &["app", "fresh"],
        "go" => &["link", "app"],
        "tap" => &["sel", "repeat", "long"],
        "type" => &["sel", "text", "submit"],
        "find" => &["sel"],
        "toggle" => &["sel", "on"],
        "scroll" => &["direction", "sel", "until"],
        "wait" => &["sel", "gone", "timeout_ms"],
        "expect" => &["cond", "timeout_ms"],
        "read" => &["sel", "as"],
        "if" => &["cond", "then", "else"],
        "for_each" => &["items", "as", "steps"],
        "call" => &["route", "args"],
        "ask_owner" => &["message"],
        _ => &[],
    }
}

fn required_fields(kind: &str) -> &'static [&'static str] {
    match kind {
        "open" => &["app"],
        "go" => &["link"],
        "tap" | "find" | "read" => &["sel"],
        "type" => &["text"],
        "toggle" => &["sel", "on"],
        "scroll" => &["direction"],
        "expect" => &["cond"],
        "if" => &["cond", "then"],
        "for_each" => &["items", "steps"],
        "call" => &["route"],
        "ask_owner" => &["message"],
        _ => &[],
    }
}

// ---- Validation ----

fn bounded(v: &Value, what: &str) -> Result<String, String> {
    match v.as_str() {
        Some(s)
            if !s.trim().is_empty()
                && s.chars().count() <= MAX_STR
                && !s.chars().any(char::is_control) =>
        {
            Ok(s.to_owned())
        }
        Some(_) => Err(format!(
            "{what} must be 1 to {MAX_STR} characters without control characters"
        )),
        None => Err(format!("{what} must be a string")),
    }
}

fn is_ident(s: &str) -> bool {
    let mut chars = s.chars();
    matches!(chars.next(), Some(c) if c.is_ascii_lowercase())
        && s.len() <= 24
        && chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_')
}

fn flag(v: &Value, what: &str) -> Result<Value, String> {
    v.as_bool()
        .map(Value::Bool)
        .ok_or(format!("{what} must be true or false"))
}

/// A selector in canonical form: a string means its words.
pub fn selector(v: &Value, depth: usize) -> Result<Value, String> {
    if let Some(s) = v.as_str() {
        return Ok(json!({ "text": bounded(&json!(s), "selector")? }));
    }
    let obj = v.as_object().ok_or(
        "a selector is words or an object with text, desc, id, role, near, inside, index, state",
    )?;
    if obj.is_empty() {
        return Err("a selector needs at least one field".into());
    }
    let mut out = Map::new();
    for (key, value) in obj {
        match key.as_str() {
            "text" | "desc" | "id" => {
                out.insert(
                    key.clone(),
                    json!(bounded(value, &format!("selector {key}"))?),
                );
            }
            "role" => {
                let r = value.as_str().unwrap_or_default();
                if !ROLES.contains(&r) {
                    return Err(format!("selector role must be one of {}", ROLES.join(", ")));
                }
                out.insert(key.clone(), json!(r));
            }
            "near" | "inside" => {
                if depth >= 1 {
                    return Err(format!("selector {key} cannot nest another near or inside"));
                }
                out.insert(key.clone(), selector(value, depth + 1)?);
            }
            "index" => {
                let n = value
                    .as_i64()
                    .filter(|n| (1..=50).contains(n))
                    .ok_or("selector index must be 1 to 50")?;
                out.insert(key.clone(), json!(n));
            }
            "state" => {
                let s = value
                    .as_object()
                    .ok_or("selector state is an object, e.g. {\"checked\": true}")?;
                let mut clean = Map::new();
                for (k, b) in s {
                    if !["checked", "selected", "enabled", "focused"].contains(&k.as_str()) {
                        return Err(format!(
                            "selector state '{}' must be checked, selected, enabled, or focused",
                            truncate(k, 24)
                        ));
                    }
                    clean.insert(k.clone(), flag(b, &format!("selector state {k}"))?);
                }
                if clean.is_empty() {
                    return Err("selector state needs a field".into());
                }
                out.insert(key.clone(), Value::Object(clean));
            }
            other => return Err(format!("unknown selector field '{}'", truncate(other, 24))),
        }
    }
    if out
        .keys()
        .all(|k| k == "index" || k == "near" || k == "inside" || k == "state")
    {
        return Err("a selector needs text, desc, id, or role".into());
    }
    Ok(Value::Object(out))
}

/// A condition in canonical form (for expect and if).
pub fn condition(v: &Value, depth: usize) -> Result<Value, String> {
    let obj = v.as_object().filter(|o| o.len() == 1).ok_or(
        "a condition is one of {text}, {gone}, {state}, {app}, {count}, {playing}, {var}, {not}",
    )?;
    let (key, value) = obj.iter().next().expect("one key");
    let clean = match key.as_str() {
        "text" | "gone" => selector(value, 0)?,
        "state" => {
            let o = value
                .as_object()
                .ok_or("state takes {sel, checked|selected|enabled|focused}")?;
            let mut c = Map::new();
            c.insert(
                "sel".into(),
                selector(o.get("sel").ok_or("state needs sel")?, 0)?,
            );
            for (k, b) in o {
                match k.as_str() {
                    "sel" => {}
                    "checked" | "selected" | "enabled" | "focused" => {
                        c.insert(k.clone(), flag(b, &format!("state {k}"))?);
                    }
                    other => return Err(format!("unknown state field '{}'", truncate(other, 24))),
                }
            }
            if c.len() < 2 {
                return Err("state needs checked, selected, enabled, or focused".into());
            }
            Value::Object(c)
        }
        "app" => json!(bounded(value, "app")?),
        "count" => {
            let o = value.as_object().ok_or("count takes {sel, min?, max?}")?;
            let mut c = Map::new();
            c.insert(
                "sel".into(),
                selector(o.get("sel").ok_or("count needs sel")?, 0)?,
            );
            for (k, n) in o {
                match k.as_str() {
                    "sel" => {}
                    "min" | "max" => {
                        let n = n
                            .as_i64()
                            .filter(|n| (0..=500).contains(n))
                            .ok_or(format!("count {k} must be 0 to 500"))?;
                        c.insert(k.clone(), json!(n));
                    }
                    other => return Err(format!("unknown count field '{}'", truncate(other, 24))),
                }
            }
            Value::Object(c)
        }
        "playing" => flag(value, "playing")?,
        "var" => {
            let o = value
                .as_object()
                .ok_or("var takes {name, equals | contains}")?;
            let name = o.get("name").and_then(Value::as_str).unwrap_or_default();
            if !is_ident(name) {
                return Err("var name is a lowercase identifier".into());
            }
            let mut c = Map::new();
            c.insert("name".into(), json!(name));
            for (k, s) in o {
                match k.as_str() {
                    "name" => {}
                    "equals" | "contains" => {
                        c.insert(k.clone(), json!(bounded(s, &format!("var {k}"))?));
                    }
                    other => return Err(format!("unknown var field '{}'", truncate(other, 24))),
                }
            }
            if c.len() != 2 {
                return Err("var takes exactly one of equals or contains".into());
            }
            Value::Object(c)
        }
        "not" => {
            if depth >= 2 {
                return Err("not cannot nest that deep".into());
            }
            condition(value, depth + 1)?
        }
        other => return Err(format!("unknown condition '{}'", truncate(other, 24))),
    };
    Ok(json!({ key.as_str(): clean }))
}

/// Checks a program and returns it in canonical form.
pub fn validate(program: &Value) -> Result<Value, String> {
    let steps = program.as_array().ok_or("steps must be a list")?;
    if steps.is_empty() || steps.len() > MAX_TOP_STEPS {
        return Err(format!("1 to {MAX_TOP_STEPS} steps"));
    }
    let mut total = 0usize;
    let mut vars = Vec::new();
    let clean = validate_block(steps, 1, &mut total, &mut vars)?;
    Ok(Value::Array(clean))
}

fn validate_block(
    steps: &[Value],
    depth: usize,
    total: &mut usize,
    vars: &mut Vec<String>,
) -> Result<Vec<Value>, String> {
    let mut out = Vec::new();
    for step in steps {
        *total += 1;
        if *total > MAX_TOTAL_STEPS {
            return Err(format!("at most {MAX_TOTAL_STEPS} steps in all"));
        }
        out.push(validate_step(step, depth, total, vars)?);
    }
    Ok(out)
}

fn validate_step(
    step: &Value,
    depth: usize,
    total: &mut usize,
    vars: &mut Vec<String>,
) -> Result<Value, String> {
    let obj = step.as_object().ok_or("each step must be an object")?;
    let kind = obj.get("do").and_then(Value::as_str).unwrap_or_default();
    if !KINDS.contains(&kind) {
        return Err(format!("each step needs do: one of {}", KINDS.join(", ")));
    }
    let allowed = kind_fields(kind);
    for key in obj.keys() {
        if !["do", "optional", "note"].contains(&key.as_str()) && !allowed.contains(&key.as_str()) {
            return Err(format!(
                "step {kind}: unknown field '{}'",
                truncate(key, 32)
            ));
        }
    }
    for req in required_fields(kind) {
        if !obj.contains_key(*req) {
            return Err(format!("step {kind}: '{req}' is required"));
        }
    }
    let mut clean = Map::new();
    clean.insert("do".into(), json!(kind));
    for (key, value) in obj {
        let what = format!("step {kind}: {key}");
        let v = match key.as_str() {
            "do" => continue,
            "optional" | "fresh" | "long" | "submit" | "gone" | "on" => flag(value, &what)?,
            "sel" | "until" => selector(value, 0).map_err(|e| format!("step {kind}: {e}"))?,
            "cond" => condition(value, 0).map_err(|e| format!("step {kind}: {e}"))?,
            "timeout_ms" => json!(
                value
                    .as_i64()
                    .filter(|n| (100..=30_000).contains(n))
                    .ok_or(format!("{what} must be 100 to 30000"))?
            ),
            "repeat" => match value {
                Value::Number(_) => json!(
                    value
                        .as_i64()
                        .filter(|n| (0..=MAX_REPEAT).contains(n))
                        .ok_or(format!("{what} must be 0 to {MAX_REPEAT}"))?
                ),
                _ => json!(bounded(value, &what)?),
            },
            "direction" => {
                let d = value.as_str().unwrap_or_default();
                if !["up", "down", "left", "right"].contains(&d) {
                    return Err(format!("{what} must be up, down, left, or right"));
                }
                json!(d)
            }
            "as" => {
                let name = value.as_str().unwrap_or_default();
                if !is_ident(name) {
                    return Err(format!("{what} is a lowercase name, e.g. price"));
                }
                if !vars.iter().any(|v| v == name) {
                    vars.push(name.to_owned());
                }
                json!(name)
            }
            "route" => {
                let r = value.as_str().unwrap_or_default();
                if !ROUTES.contains(&r) {
                    return Err(format!("{what} must be one of {}", ROUTES.join(", ")));
                }
                json!(r)
            }
            "args" => {
                let o = value
                    .as_object()
                    .ok_or(format!("{what} must be an object"))?;
                if o.len() > 4 {
                    return Err(format!("{what} takes at most 4 fields"));
                }
                for (k, a) in o {
                    if !matches!(a, Value::Bool(_) | Value::Number(_)) {
                        bounded(a, &format!("{what}.{k}"))?;
                    }
                }
                value.clone()
            }
            "items" => match value {
                Value::Array(items) => {
                    if items.is_empty() || items.len() > MAX_ITEMS {
                        return Err(format!("{what} takes 1 to {MAX_ITEMS} items"));
                    }
                    for item in items {
                        match item {
                            Value::String(_) => {
                                bounded(item, &what)?;
                            }
                            Value::Number(_) => {}
                            Value::Object(o) if o.len() <= 6 => {
                                for (k, f) in o {
                                    if !is_ident(k) {
                                        return Err(format!(
                                            "{what}: field names are lowercase identifiers"
                                        ));
                                    }
                                    if !matches!(f, Value::Number(_)) {
                                        bounded(f, &format!("{what}.{k}"))?;
                                    }
                                }
                            }
                            _ => {
                                return Err(format!(
                                    "{what}: each item is text, a number, or an object of up to 6 fields"
                                ));
                            }
                        }
                    }
                    value.clone()
                }
                _ => return Err(format!("{what} must be a list")),
            },
            "then" | "else" | "steps" => {
                if depth >= MAX_DEPTH {
                    return Err(format!("step {kind}: blocks nest at most {MAX_DEPTH} deep"));
                }
                let list = value
                    .as_array()
                    .ok_or(format!("{what} must be a list of steps"))?;
                if list.is_empty() || list.len() > 20 {
                    return Err(format!("{what} takes 1 to 20 steps"));
                }
                Value::Array(validate_block(list, depth + 1, total, vars)?)
            }
            "app" | "link" | "text" | "message" | "note" => json!(bounded(value, &what)?),
            _ => {
                return Err(format!(
                    "step {kind}: unknown field '{}'",
                    truncate(key, 32)
                ));
            }
        };
        clean.insert(key.clone(), v);
    }
    if kind == "for_each" && !clean.contains_key("as") {
        clean.insert("as".into(), json!("item"));
    }
    if kind == "go" {
        let link = clean["link"].as_str().unwrap_or_default();
        if !link.contains(" > ") && !clean.contains_key("app") {
            return Err("step go: a link needs app, the package that opens it".into());
        }
    }
    Ok(Value::Object(clean))
}

fn truncate(s: &str, max: usize) -> String {
    s.chars().take(max).collect()
}

// ---- Selectors on the live screen ----

/// What a step does with the element it finds.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Want {
    Tap,
    Type,
    Any,
}

/// How a selector resolved.
#[derive(Debug, PartialEq, Eq)]
pub enum Resolve {
    Found(String),
    Ambiguous(Vec<String>),
    NotFound,
}

/// Letters and digits only: "Lay's India's" and "lays indias" fold alike.
fn fold(s: &str) -> String {
    s.chars()
        .filter(|c| c.is_alphanumeric() || c.is_whitespace())
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase()
}

fn words_level(c: &str, t: &str) -> u8 {
    let exact = match_level(&norm(c), &norm(t));
    if exact > 0 {
        return exact;
    }
    let (fc, ft) = (fold(c), fold(t));
    if ft.is_empty() {
        0
    } else if fc == ft {
        3
    } else {
        u8::from(fc.contains(&ft))
    }
}

/// How well a node's words match; "ADD || +" scores the best alternative.
fn text_score(node: &UiNode, target: &str) -> u8 {
    target
        .split("||")
        .filter(|t| !t.trim().is_empty())
        .map(|t| text_score_one(node, t.trim()))
        .max()
        .unwrap_or(0)
}

/// How well a node's own words (text, description, guessed name) match.
fn text_score_one(node: &UiNode, t: &str) -> u8 {
    let own = [node.text.as_deref(), node.description.as_deref()]
        .into_iter()
        .flatten()
        .map(|c| words_level(c, t))
        .max()
        .unwrap_or(0);
    if own > 0 {
        return own;
    }
    let Some(head) = node.label.as_deref().map(|l| label_parts(l).0) else {
        return 0;
    };
    let level = words_level(head, t);
    if level > 0 {
        return level;
    }
    let nt = norm(t);
    let nh = norm(head);
    let same = icon_class(&nt).is_some_and(|class| {
        icon_class(&nh) == Some(class) || nh.split(' ').any(|w| icon_class(w) == Some(class))
    });
    if same { 2 } else { 0 }
}

fn has_word(node: &UiNode, class: &str) -> bool {
    let fields = [
        node.text.as_deref(),
        node.description.as_deref(),
        node.label.as_deref().map(|l| label_parts(l).0),
        node.resource_id
            .as_deref()
            .map(|r| r.rsplit('/').next().unwrap_or(r)),
    ];
    fields.into_iter().flatten().any(|f| {
        let n = norm(&f.replace(['_', '-'], " "));
        icon_class(&n) == Some(class) || n.split(' ').any(|w| icon_class(w) == Some(class))
    })
}

fn role_matches(node: &UiNode, role: &str) -> bool {
    let r = node.role.as_str();
    match role {
        "button" => {
            r.contains("Button") || (node.clickable && !node.editable && node.checked.is_none())
        }
        "input" => node.editable,
        "switch" => node.checked.is_some(),
        "tab" => r.contains("Tab"),
        "row" => node.position.is_some(),
        "card" => {
            node.clickable
                && (r.ends_with("Layout")
                    || r.contains("ViewGroup")
                    || r.contains("Card")
                    || r == "View")
        }
        "video" => {
            ["SurfaceView", "TextureView", "VideoView"].contains(&r)
                || r.contains("Video")
                || r.contains("Player")
        }
        "image" => r.contains("Image"),
        class => has_word(node, class),
    }
}

fn state_matches(node: &UiNode, state: &Map<String, Value>) -> bool {
    state.iter().all(|(k, v)| {
        let want = v.as_bool().unwrap_or(false);
        match k.as_str() {
            "checked" => node.checked == Some(want),
            "selected" => node.selected == want,
            "enabled" => node.enabled == want,
            "focused" => node.focused == want,
            _ => false,
        }
    })
}

/// A node's score for a selector's own fields (not near, inside, or index); 0 = no match.
fn score(node: &UiNode, sel: &Map<String, Value>) -> u32 {
    let mut total = 0u32;
    let s = |k: &str| sel.get(k).and_then(Value::as_str);
    if let Some(t) = s("text") {
        let level = text_score(node, t);
        if level == 0 {
            return 0;
        }
        total += u32::from(level) * 10;
    }
    if let Some(d) = s("desc") {
        let level = node.description.as_deref().map_or(0, |c| words_level(c, d));
        if level == 0 {
            return 0;
        }
        total += u32::from(level) * 10;
    }
    if let Some(id) = s("id") {
        let rid = node
            .resource_id
            .as_deref()
            .unwrap_or_default()
            .to_lowercase();
        let want = id.to_lowercase();
        let entry = rid.rsplit('/').next().unwrap_or_default();
        let level = if entry == want || rid == want {
            3
        } else if rid.contains(&want) {
            2
        } else {
            0
        };
        if level == 0 {
            return 0;
        }
        total += level * 10;
    }
    if let Some(role) = s("role") {
        if !role_matches(node, role) {
            return 0;
        }
        total += 5;
    }
    if let Some(state) = sel.get("state").and_then(Value::as_object)
        && !state_matches(node, state)
    {
        return 0;
    }
    total
}

fn area(n: &UiNode) -> i64 {
    let b = n.bounds;
    if b.is_empty() {
        0
    } else {
        i64::from(b.right - b.left) * i64::from(b.bottom - b.top)
    }
}

fn ancestors<'a>(
    node: &'a UiNode,
    by_id: &std::collections::HashMap<&str, &'a UiNode>,
) -> Vec<&'a str> {
    let mut out = Vec::new();
    let mut parent = node.parent.as_deref();
    while let Some(p) = parent {
        if out.len() >= 64 || out.contains(&p) {
            break;
        }
        out.push(p);
        parent = by_id.get(p).and_then(|n| n.parent.as_deref());
    }
    out
}

/// What the AI sees for a candidate: its id and its words.
pub fn describe(node: &UiNode) -> String {
    let words = node
        .text
        .as_deref()
        .or(node.description.as_deref())
        .or(node.label.as_deref())
        .unwrap_or(&node.role);
    format!("{}: {}", node.id, truncate(words, 80))
}

/// Every node that matches, outer elements only (a button and its own label count once).
fn matches<'a>(
    nodes: &'a [UiNode],
    sel: &Map<String, Value>,
    want: Want,
) -> Vec<(&'a UiNode, u32)> {
    let by_id: std::collections::HashMap<&str, &UiNode> =
        nodes.iter().map(|n| (n.id.as_str(), n)).collect();
    // Inside: the element the words name, or a container around it smaller than the page
    // ("Not now" inside "Rate this app" means inside the dialog with that title).
    let page = nodes.iter().map(area).max().unwrap_or(0);
    let inside: Option<Vec<&str>> = sel.get("inside").and_then(Value::as_object).map(|inner| {
        let mut containers = Vec::new();
        for n in nodes.iter().filter(|n| score(n, inner) > 0) {
            containers.push(n.id.as_str());
            for a in ancestors(n, &by_id) {
                if by_id.get(a).is_none_or(|p| area(p) * 10 >= page * 6) {
                    break;
                }
                containers.push(a);
            }
        }
        containers
    });
    let mut found: Vec<(&UiNode, u32)> = nodes
        .iter()
        .filter(|n| usable(n))
        .filter(|n| want != Want::Type || n.editable)
        .map(|n| (n, score(n, sel)))
        .filter(|(_, s)| *s > 0)
        .filter(|(n, _)| {
            inside.as_ref().is_none_or(|containers| {
                ancestors(n, &by_id).iter().any(|a| containers.contains(a))
            })
        })
        .collect();
    let ids: Vec<&str> = found.iter().map(|(n, _)| n.id.as_str()).collect();
    found.retain(|(n, _)| !ancestors(n, &by_id).iter().any(|a| ids.contains(a)));
    if want == Want::Tap && found.iter().any(|(n, _)| n.clickable || n.long_clickable) {
        // Something tappable matches: words on a plain label next to it are not the target.
        let has_clickable_ancestor = |n: &UiNode| {
            ancestors(n, &by_id)
                .iter()
                .any(|a| by_id.get(a).is_some_and(|p| p.clickable))
        };
        found.retain(|(n, _)| n.clickable || n.long_clickable || has_clickable_ancestor(n));
    }
    found
}

/// Finds the one element a selector means on [nodes]; two equally good fits are ambiguous.
pub fn resolve(nodes: &[UiNode], sel: &Value, want: Want) -> Resolve {
    let Some(sel) = sel.as_object() else {
        return Resolve::NotFound;
    };
    let mut found = matches(nodes, sel, want);
    if found.is_empty() {
        return Resolve::NotFound;
    }
    if let Some(index) = sel.get("index").and_then(Value::as_u64) {
        found.sort_by_key(|(n, _)| (n.bounds.top, n.bounds.left));
        return found
            .get(index as usize - 1)
            .map_or(Resolve::NotFound, |(n, _)| Resolve::Found(n.id.clone()));
    }
    let near = sel.get("near").and_then(Value::as_object);
    let anchor = match near {
        None => None,
        Some(near_sel) => {
            let pool = matches(nodes, near_sel, Want::Any);
            let pool: Vec<_> = pool.into_iter().filter(|(n, _)| !n.editable).collect();
            let Some((best, _)) = pool
                .iter()
                .max_by_key(|(n, s)| (*s, std::cmp::Reverse((n.bounds.top, n.bounds.left))))
            else {
                return Resolve::NotFound;
            };
            let words = near_sel.get("text").and_then(Value::as_str).map(norm);
            Some((best.bounds.center(), words))
        }
    };
    // Rank: in the near words' card, then score, then distance to the anchor, then reading order.
    let key = |n: &UiNode, s: u32| -> (bool, u32, i64) {
        match &anchor {
            None => (false, s, 0),
            Some(((ax, ay), words)) => {
                let (cx, cy) = n.bounds.center();
                let distance = i64::from((cy - ay).abs()) * 2 + i64::from((cx - ax).abs());
                let in_card = words.as_ref().is_some_and(|w| {
                    n.label
                        .as_deref()
                        .is_some_and(|l| norm(label_parts(l).1).contains(w.as_str()))
                });
                (in_card, s, distance)
            }
        }
    };
    found.sort_by(|(a, sa), (b, sb)| {
        let (ka, kb) = (key(a, *sa), key(b, *sb));
        kb.0.cmp(&ka.0)
            .then(kb.1.cmp(&ka.1))
            .then(ka.2.cmp(&kb.2))
            .then((a.bounds.top, a.bounds.left).cmp(&(b.bounds.top, b.bounds.left)))
    });
    let (best, best_score) = found[0];
    if let Some(&(second, second_score)) = found.get(1) {
        let (kb, ks) = (key(best, best_score), key(second, second_score));
        // Within 10 %: the same card and score, and (with near) nearly the same distance.
        let close =
            kb.0 == ks.0 && kb.1 == ks.1 && (anchor.is_none() || (ks.2 - kb.2) * 10 <= ks.2.max(1));
        if close {
            return Resolve::Ambiguous(
                found
                    .iter()
                    .take(MAX_CANDIDATES)
                    .filter(|(n, s)| {
                        let k = key(n, *s);
                        k.0 == kb.0 && k.1 == kb.1
                    })
                    .map(|(n, _)| describe(n))
                    .collect(),
            );
        }
    }
    Resolve::Found(best.id.clone())
}

/// Whether [cond] holds on [obs] with [vars].
pub fn holds(obs: &Observation, vars: &Map<String, Value>, cond: &Value) -> bool {
    let Some((key, value)) = cond.as_object().and_then(|o| o.iter().next()) else {
        return false;
    };
    let nodes = &obs.nodes;
    match key.as_str() {
        "text" => resolve(nodes, value, Want::Any) != Resolve::NotFound,
        "gone" => resolve(nodes, value, Want::Any) == Resolve::NotFound,
        "state" => {
            let Some(sel) = value.get("sel").and_then(Value::as_object) else {
                return false;
            };
            let mut state = value.as_object().cloned().unwrap_or_default();
            state.remove("sel");
            matches(nodes, sel, Want::Any)
                .first()
                .is_some_and(|(n, _)| state_matches(n, &state))
        }
        "app" => obs.package.as_deref() == value.as_str(),
        "count" => {
            let Some(sel) = value.get("sel").and_then(Value::as_object) else {
                return false;
            };
            let n = matches(nodes, sel, Want::Any).len() as i64;
            value
                .get("min")
                .and_then(Value::as_i64)
                .is_none_or(|m| n >= m)
                && value
                    .get("max")
                    .and_then(Value::as_i64)
                    .is_none_or(|m| n <= m)
        }
        "playing" => {
            // A visible Pause control means something is playing.
            let pause = nodes.iter().any(|n| {
                usable(n)
                    && [
                        n.text.as_deref(),
                        n.description.as_deref(),
                        n.label.as_deref().map(|l| label_parts(l).0),
                    ]
                    .into_iter()
                    .flatten()
                    .any(|w| norm(w) == "pause")
            });
            pause == value.as_bool().unwrap_or(true)
        }
        "var" => {
            let name = value["name"].as_str().unwrap_or_default();
            let have = vars.get(name).map(scalar_text).unwrap_or_default();
            if let Some(e) = value.get("equals").and_then(Value::as_str) {
                norm(&have) == norm(e)
            } else if let Some(c) = value.get("contains").and_then(Value::as_str) {
                norm(&have).contains(&norm(c))
            } else {
                false
            }
        }
        "not" => !holds(obs, vars, value),
        _ => false,
    }
}

fn scalar_text(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        Value::Null => String::new(),
        other => other.to_string(),
    }
}

/// Fills `{name}` and `{name.field}` from [vars]; other braces stay as written.
pub fn fill(template: &str, vars: &Map<String, Value>) -> Result<String, String> {
    let mut out = String::new();
    let mut rest = template;
    while let Some(start) = rest.find('{') {
        out.push_str(&rest[..start]);
        let after = &rest[start + 1..];
        let Some(end) = after.find('}') else {
            out.push_str(&rest[start..]);
            return Ok(out);
        };
        let inner = &after[..end];
        let (name, field) = match inner.split_once('.') {
            Some((n, f)) => (n, Some(f)),
            None => (inner, None),
        };
        if is_ident(name) && field.is_none_or(is_ident) {
            let value = vars
                .get(name)
                .ok_or(format!("{{{inner}}}: no value named {name} yet"))?;
            let value = match field {
                None => value,
                Some(f) => value
                    .get(f)
                    .ok_or(format!("{{{inner}}}: {name} has no field {f}"))?,
            };
            out.push_str(&scalar_text(value));
        } else {
            out.push_str(&rest[start..start + end + 2]);
        }
        rest = &after[end + 1..];
    }
    out.push_str(rest);
    Ok(out)
}

/// A selector with every word filled from [vars].
fn fill_value(v: &Value, vars: &Map<String, Value>) -> Result<Value, String> {
    Ok(match v {
        Value::String(s) => json!(fill(s, vars)?),
        Value::Object(o) => {
            let mut out = Map::new();
            for (k, x) in o {
                out.insert(k.clone(), fill_value(x, vars)?);
            }
            Value::Object(out)
        }
        Value::Array(a) => Value::Array(
            a.iter()
                .map(|x| fill_value(x, vars))
                .collect::<Result<_, _>>()?,
        ),
        other => other.clone(),
    })
}

/// The dismiss button of a pop-up on screen, if one is shown.
pub fn popup_dismiss(nodes: &[UiNode]) -> Option<String> {
    nodes
        .iter()
        .filter(|n| usable(n) && n.clickable)
        .find(|n| {
            [n.text.as_deref(), n.description.as_deref()]
                .into_iter()
                .flatten()
                .any(|w| POPUP_WORDS.contains(&norm(w).as_str()))
        })
        .map(|n| n.id.clone())
}

// ---- The tool ----

pub fn definition() -> Value {
    tool(
        NAME,
        "Run a program",
        "Run a whole task on the phone in one call: think once, send every step. Steps (field do): \
         open {app: name or package, fresh?}; go {link, app} (an app link) or go {link: \"settings > wifi\"}; \
         tap {sel, repeat?, long?}; type {sel?, text, submit?}; find {sel}; toggle {sel, on}; \
         scroll {direction, sel?, until?}; back; home; wait {sel?, gone?, timeout_ms?} (no sel: let the screen settle); \
         expect {cond, timeout_ms?}; read {sel, as} (stores the element's words as {as}); \
         if {cond, then, else?}; for_each {items, as?, steps} ({item} or {item.field} in any text); \
         call {route, args} (media {action: play_pause|next|previous}, volume {stream?, percent}, flashlight {on}, \
         brightness {percent}, ringer {mode}, do_not_disturb {on}, panel {panel}, link {url, app}, clipboard {text}, \
         timer {minutes?, seconds?}, alarm {hour, minute}: the phone's own controls, no screen); ask_owner {message}. Any step may be optional (skipped when its \
         element is not there) and have a note. A selector (sel) is the element's words, or {text, desc, id, role \
         (button, input, row, card, switch, tab, video, image, or an icon: plus, minus, send, back, search, close, \
         cart, heart, share, menu), near: sel (in the same row or card), inside: sel (a dialog, list, or sheet), \
         index (the n-th match, top to bottom), state: {checked|selected|enabled|focused}}. Conditions: {text: sel}, \
         {gone: sel}, {state: {sel, checked: true}}, {app: package}, {count: {sel, min?, max?}}, {playing: true}, \
         {var: {name, equals|contains}}, {not: cond}. If two elements fit a selector equally well, the step fails \
         and lists both: add near, inside, role, or index. A step that finds nothing first reads the screen again, \
         closes a known pop-up (Not now, Skip, Close …), and scrolls to it. Every phone action goes through the same \
         rules as the single tools (apps the owner allows, approvals, refused fields). On a stop the answer is JSON \
         with the step, the reason, the candidates, what was tried, and resume: call run_program again with the \
         same steps, from_step, and vars to continue; finished steps never run again.",
        false,
        json!({
            "device_id": device_id_schema(),
            "steps": { "type": "array", "minItems": 1, "maxItems": MAX_TOP_STEPS, "items": { "type": "object" }, "description": "The program, in order." },
            "from_step": { "type": "string", "maxLength": 20, "description": "Continue from this step, as a stopped run's resume names it (\"4\", or \"3.2.1\" inside a loop or branch)." },
            "vars": { "type": "object", "description": "Values from a stopped run's resume, so read steps need not run again." },
        }),
        &["steps"],
    )
}

/// Why a run stopped.
enum Stop {
    Failed {
        path: String,
        command: String,
        selector: Option<Value>,
        reason: &'static str,
        message: String,
        candidates: Vec<String>,
        tried: Vec<&'static str>,
    },
    Error(String, String, ProtocolError),
    Owner(String, String),
    Paused(String),
}

struct Runner<'a> {
    state: &'a Arc<AppState>,
    device: String,
    vars: Map<String, Value>,
    actions: usize,
    started: Instant,
    lines: Vec<String>,
    last: Option<Value>,
    resume: Option<Vec<usize>>,
    routes: Vec<String>,
}

fn path_text(path: &[usize]) -> String {
    path.iter()
        .map(usize::to_string)
        .collect::<Vec<_>>()
        .join(".")
}

fn parse_path(s: &str) -> Option<Vec<usize>> {
    let parts: Option<Vec<usize>> = s
        .split('.')
        .map(|p| p.parse().ok().filter(|n| *n >= 1))
        .collect();
    parts.filter(|p| !p.is_empty() && p.len() <= 7)
}

fn first_text(v: &Value) -> String {
    v.pointer("/content/0/text")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_owned()
}

fn first_is_headline(part: &Value) -> bool {
    part["text"]
        .as_str()
        .is_some_and(|t| t.ends_with("The screen after the action:"))
}

/// The step after [path] at the same level, for resuming after a step that already happened.
fn next_path(path: &[usize]) -> Vec<usize> {
    let mut next = path.to_vec();
    if let Some(last) = next.last_mut() {
        *last += 1;
    }
    next
}

impl Runner<'_> {
    async fn act(&mut self, tool_name: &str, mut args: Value) -> Result<Value, ProtocolError> {
        args["device_id"] = json!(self.device);
        self.actions += 1;
        let v = Box::pin(super::run_tool_logged(self.state, tool_name, &args)).await?;
        self.last = Some(v.clone());
        Ok(v)
    }

    fn latest(&self) -> Option<Arc<Observation>> {
        self.state.devices.latest_observation(&self.device)
    }

    /// The latest screen, read now when there is none.
    async fn screen(&mut self, fresh: bool) -> Result<Arc<Observation>, ProtocolError> {
        if !fresh && let Some(obs) = self.latest() {
            return Ok(obs);
        }
        self.act("observe", json!({ "screenshot": false })).await?;
        self.latest().ok_or_else(|| {
            ProtocolError::new(ErrorCode::DeviceUnavailable, "could not read the screen")
        })
    }

    fn over_budget(&self) -> bool {
        self.actions >= MAX_ACTIONS || self.started.elapsed().as_millis() > RUN_BUDGET_MS
    }

    /// Whether a step at [path] was already done in an earlier call (resume).
    fn skip(&mut self, path: &[usize]) -> bool {
        let Some(r) = &self.resume else {
            return false;
        };
        if r.starts_with(path) {
            if r.len() == path.len() {
                self.resume = None;
            }
            return false;
        }
        if path < r.as_slice() {
            return true;
        }
        self.resume = None;
        false
    }

    /// Finds [sel] on the screen, recovering when it is not there.
    async fn locate(
        &mut self,
        path: &[usize],
        command: &str,
        sel: &Value,
        want: Want,
    ) -> Result<(String, String), Stop> {
        let fail = |reason: &'static str,
                    message: String,
                    candidates: Vec<String>,
                    tried: Vec<&'static str>| Stop::Failed {
            path: path_text(path),
            command: command.to_owned(),
            selector: Some(sel.clone()),
            reason,
            message,
            candidates,
            tried,
        };
        let mut tried: Vec<&'static str> = Vec::new();
        for attempt in ["first", "settle", "popup", "scroll", "retry"] {
            let fresh = attempt != "first";
            match attempt {
                "popup" => {
                    let Some(obs) = self.latest() else { continue };
                    let Some(dismiss) = popup_dismiss(&obs.nodes) else {
                        continue;
                    };
                    tried.push("popup");
                    let args =
                        json!({ "observation_id": obs.observation_id, "element_id": dismiss });
                    if let Err(e) = self.act("tap", args).await {
                        return Err(Stop::Error(path_text(path), command.to_owned(), e));
                    }
                }
                "scroll" => {
                    let goal = sel
                        .get("text")
                        .or_else(|| sel.pointer("/near/text"))
                        .and_then(Value::as_str)
                        .map(str::to_owned);
                    let (Some(goal), Some(obs)) = (goal, self.latest()) else {
                        continue;
                    };
                    tried.push("scroll");
                    let args = json!({ "observation_id": obs.observation_id, "text": goal, "max_swipes": 4 });
                    // Not found or nothing to scroll: the look below decides.
                    let _ = self.act("scroll_to", args).await;
                }
                "settle" | "retry" => tried.push(attempt),
                _ => {}
            }
            let obs = match self
                .screen(fresh && attempt != "popup" && attempt != "scroll")
                .await
            {
                Ok(obs) => obs,
                Err(e) => return Err(Stop::Error(path_text(path), command.to_owned(), e)),
            };
            match resolve(&obs.nodes, sel, want) {
                Resolve::Found(id) => return Ok((obs.observation_id.clone(), id)),
                Resolve::Ambiguous(c) => {
                    return Err(fail(
                        "ambiguous",
                        "two or more elements fit equally well; add near, inside, role, or index"
                            .into(),
                        c,
                        tried,
                    ));
                }
                Resolve::NotFound => {}
            }
            if self.over_budget() {
                break;
            }
        }
        Err(fail(
            "not_found",
            "nothing on the screen fits".into(),
            Vec::new(),
            tried,
        ))
    }

    fn node(&self, id: &str) -> Option<UiNode> {
        self.latest()?.nodes.iter().find(|n| n.id == id).cloned()
    }

    fn done(&mut self, path: &[usize], what: String) {
        self.lines.push(format!("{}. ✓ {what}", path_text(path)));
    }

    fn skipped(&mut self, path: &[usize], what: String) {
        self.lines.push(format!(
            "{}. – skipped, not on screen: {what}",
            path_text(path)
        ));
    }

    async fn block(&mut self, steps: &[Value], prefix: &[usize]) -> Result<(), Stop> {
        for (i, step) in steps.iter().enumerate() {
            let mut path = prefix.to_vec();
            path.push(i + 1);
            if self.skip(&path) {
                continue;
            }
            if self.over_budget() {
                return Err(Stop::Paused(path_text(&path)));
            }
            let step = fill_step(step, &self.vars).map_err(|e| Stop::Failed {
                path: path_text(&path),
                command: step["do"].as_str().unwrap_or_default().to_owned(),
                selector: None,
                reason: "bad_value",
                message: e,
                candidates: Vec::new(),
                tried: Vec::new(),
            })?;
            match Box::pin(self.step(&step, &path)).await {
                Err(Stop::Failed {
                    reason: "not_found",
                    ..
                }) if step["optional"] == json!(true) => {
                    self.skipped(&path, label(&step));
                }
                other => other?,
            }
        }
        Ok(())
    }

    async fn step(&mut self, step: &Value, path: &[usize]) -> Result<(), Stop> {
        let kind = step["do"].as_str().unwrap_or_default().to_owned();
        let s = |k: &str| {
            step.get(k)
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_owned()
        };
        let err = |e: ProtocolError| Stop::Error(path_text(path), kind.clone(), e);
        let fail = |reason: &'static str, message: String| Stop::Failed {
            path: path_text(path),
            command: kind.clone(),
            selector: step.get("sel").cloned(),
            reason,
            message,
            candidates: Vec::new(),
            tried: Vec::new(),
        };
        match kind.as_str() {
            "open" => {
                let app = s("app");
                let package = if app.contains('.') && !app.contains(' ') {
                    app.clone()
                } else {
                    let list = self
                        .act("list_apps", json!({ "query": app }))
                        .await
                        .map_err(err)?;
                    match pick_app(&first_text(&list), &app) {
                        Ok(p) => p,
                        Err(c) if c.is_empty() => {
                            return Err(fail(
                                "not_found",
                                format!("no app named {}", quote(&app, 60)),
                            ));
                        }
                        Err(c) => {
                            return Err(Stop::Failed {
                                path: path_text(path),
                                command: kind.clone(),
                                selector: None,
                                reason: "ambiguous",
                                message: format!(
                                    "several apps match {}; name the package",
                                    quote(&app, 60)
                                ),
                                candidates: c,
                                tried: Vec::new(),
                            });
                        }
                    }
                };
                let fresh = step["fresh"] == json!(true);
                self.act("launch_app", json!({ "package": package, "fresh": fresh }))
                    .await
                    .map_err(err)?;
                self.done(path, format!("open {package}"));
            }
            "go" => {
                let link = s("link");
                if let Some((app, page)) = link.split_once(" > ") {
                    let panel = norm(page).replace([' ', '-'], "_");
                    let panel = match panel.as_str() {
                        "hotspot" | "tethering" | "mobile_data" => "internet".to_owned(),
                        "wi_fi" => "wifi".to_owned(),
                        _ => panel,
                    };
                    if norm(app) != "settings"
                        || !latch_protocol::SettingsPanel::ALL
                            .iter()
                            .any(|p| p.as_str() == panel)
                    {
                        return Err(fail(
                            "route_unavailable",
                            "app maps are not built yet (R73); only \"settings > <page>\" for the phone's settings pages works".into(),
                        ));
                    }
                    self.routes.push("panel".into());
                    self.act(
                        "phone_control",
                        json!({ "control": "panel", "panel": panel }),
                    )
                    .await
                    .map_err(err)?;
                    self.done(path, format!("go settings > {panel} (route: panel)"));
                } else {
                    self.routes.push("link".into());
                    self.act("open_link", json!({ "url": link, "package": s("app") }))
                        .await
                        .map_err(err)?;
                    self.done(path, format!("go {} (route: link)", quote(&link, 60)));
                }
            }
            "tap" => {
                let sel = step["sel"].clone();
                let times = match &step["repeat"] {
                    Value::Null => 1,
                    Value::Number(n) => n.as_i64().unwrap_or(1),
                    v => v
                        .as_str()
                        .unwrap_or_default()
                        .trim()
                        .parse::<i64>()
                        .map_err(|_| fail("bad_value", "repeat is not a whole number".into()))?,
                }
                .clamp(0, MAX_REPEAT);
                let mut quiet = false;
                for rep in 0..times {
                    if self.over_budget() {
                        return Err(Stop::Paused(path_text(path)));
                    }
                    let (obs, el) = self
                        .locate(path, &kind, &sel, Want::Tap)
                        .await
                        .map_err(|e| with_progress(e, rep, times))?;
                    let args = json!({ "observation_id": obs, "element_id": el, "long_press": step["long"] == json!(true) });
                    let v = match self.act("tap", args.clone()).await {
                        Err(e) if e.code == ErrorCode::StaleObservation => {
                            let (obs, el) = self.locate(path, &kind, &sel, Want::Tap).await?;
                            let mut retry = args;
                            retry["observation_id"] = json!(obs);
                            retry["element_id"] = json!(el);
                            self.act("tap", retry).await.map_err(err)?
                        }
                        other => other.map_err(err)?,
                    };
                    quiet |=
                        first_text(&v).starts_with("Done, but the screen did not visibly change");
                }
                let mut what = label(step);
                if quiet {
                    what.push_str(" (once with no visible change)");
                }
                self.done(path, what);
            }
            "type" => {
                let (obs, el) = match step.get("sel") {
                    Some(sel) => self.locate(path, &kind, sel, Want::Type).await?,
                    None => {
                        let obs = self.screen(false).await.map_err(err)?;
                        let field = obs
                            .nodes
                            .iter()
                            .filter(|n| usable(n) && n.editable)
                            .max_by_key(|n| {
                                (n.focused, std::cmp::Reverse((n.bounds.top, n.bounds.left)))
                            })
                            .map(|n| n.id.clone())
                            .ok_or_else(|| {
                                fail("not_found", "no text field on the screen".into())
                            })?;
                        (obs.observation_id.clone(), field)
                    }
                };
                let args = json!({ "observation_id": obs, "element_id": el, "text": s("text"), "submit": step["submit"] == json!(true) });
                let v = self.act("type_text", args).await.map_err(err)?;
                if first_text(&v).starts_with("Typed, but Enter did nothing") {
                    return Err(fail(
                        "not_submitted",
                        "typed, but Enter did nothing visible; tap the app's own Send or Search"
                            .into(),
                    ));
                }
                self.done(path, label(step));
            }
            "find" => {
                self.locate(path, &kind, &step["sel"], Want::Any).await?;
                self.done(path, label(step));
            }
            "toggle" => {
                let want = step["on"] == json!(true);
                let (obs, el) = self.locate(path, &kind, &step["sel"], Want::Any).await?;
                let nodes = self.latest().map(|o| o.nodes.clone()).unwrap_or_default();
                let Some(switch) = switch_for(&nodes, &el) else {
                    return Err(fail(
                        "no_switch",
                        "found it, but it has no on/off state".into(),
                    ));
                };
                if switch.checked == Some(want) {
                    self.done(
                        path,
                        format!(
                            "{} (already {})",
                            label(step),
                            if want { "on" } else { "off" }
                        ),
                    );
                    return Ok(());
                }
                self.act(
                    "tap",
                    json!({ "observation_id": obs, "element_id": switch.id }),
                )
                .await
                .map_err(err)?;
                let now = self.latest().map(|o| o.nodes.clone()).unwrap_or_default();
                let after = match resolve(&now, &step["sel"], Want::Any) {
                    Resolve::Found(id) => switch_for(&now, &id).and_then(|n| n.checked),
                    _ => None,
                };
                if after != Some(want) {
                    return Err(fail(
                        "state_unchanged",
                        "tapped, but the switch did not change".into(),
                    ));
                }
                self.done(path, label(step));
            }
            "scroll" => {
                let obs = self.screen(false).await.map_err(err)?;
                let container = match step.get("sel") {
                    Some(sel) => Some(self.locate(path, &kind, sel, Want::Any).await?.1),
                    None => None,
                };
                if let Some(until) = step.get("until") {
                    let Some(text) = until.get("text").and_then(Value::as_str) else {
                        return Err(fail("bad_value", "until needs text".into()));
                    };
                    let mut args = json!({ "observation_id": obs.observation_id, "text": text, "direction": s("direction") });
                    if let Some(c) = container {
                        args["element_id"] = json!(c);
                    }
                    let v = self.act("scroll_to", args).await.map_err(err)?;
                    if first_text(&v).starts_with("Did not find") {
                        return Err(Stop::Failed {
                            path: path_text(path),
                            command: kind.clone(),
                            selector: Some(until.clone()),
                            reason: "not_found",
                            message: "scrolling both ways did not reveal it".into(),
                            candidates: Vec::new(),
                            tried: vec!["scroll"],
                        });
                    }
                } else {
                    let obs = self.screen(false).await.map_err(err)?;
                    let mut args = json!({ "observation_id": obs.observation_id, "direction": s("direction") });
                    if let Some(c) = container {
                        args["element_id"] = json!(c);
                    }
                    self.act("scroll", args).await.map_err(err)?;
                }
                self.done(path, label(step));
            }
            "back" | "home" => {
                self.act("press", json!({ "button": kind }))
                    .await
                    .map_err(err)?;
                self.done(path, kind.clone());
            }
            "wait" => {
                match step
                    .get("sel")
                    .and_then(|s| s.get("text"))
                    .and_then(Value::as_str)
                {
                    Some(text) => {
                        let timeout = step
                            .get("timeout_ms")
                            .and_then(Value::as_i64)
                            .unwrap_or(5_000);
                        let v = self
                            .act("wait_for", json!({ "text": text, "gone": step["gone"] == json!(true), "timeout_ms": timeout }))
                            .await
                            .map_err(err)?;
                        let t = first_text(&v);
                        if t.contains(" did not appear within ")
                            || t.contains(" was still on screen after ")
                        {
                            return Err(fail(
                                "not_found",
                                format!("still not as expected after {timeout} ms"),
                            ));
                        }
                    }
                    None => {
                        self.screen(true).await.map_err(err)?;
                    }
                }
                self.done(path, label(step));
            }
            "expect" => {
                let cond = &step["cond"];
                let timeout = step.get("timeout_ms").and_then(Value::as_i64);
                if let (Some(key), Some(text)) = (
                    cond.as_object().and_then(|o| o.keys().next().cloned()),
                    cond.as_object()
                        .and_then(|o| o.values().next())
                        .and_then(|s| s.get("text"))
                        .and_then(Value::as_str),
                ) && (key == "text" || key == "gone")
                {
                    // Words to appear or leave: wait on the phone instead of looking again and again.
                    let _ = self
                        .act("wait_for", json!({ "text": text, "gone": key == "gone", "timeout_ms": timeout.unwrap_or(1_500) }))
                        .await;
                }
                let obs = self.screen(true).await.map_err(err)?;
                if !holds(&obs, &self.vars, cond) {
                    return Err(Stop::Failed {
                        path: path_text(path),
                        command: kind.clone(),
                        selector: Some(cond.clone()),
                        reason: "expect_failed",
                        message: "the screen is not as the program expected".into(),
                        candidates: Vec::new(),
                        tried: vec!["settle"],
                    });
                }
                self.done(path, label(step));
            }
            "read" => {
                let (_, el) = self.locate(path, &kind, &step["sel"], Want::Any).await?;
                let node = self
                    .node(&el)
                    .ok_or_else(|| fail("not_found", "it left the screen".into()))?;
                let words = node
                    .text
                    .or(node.description)
                    .or(node.state)
                    .or(node.label)
                    .unwrap_or_default();
                let name = s("as");
                self.lines.push(format!(
                    "{}. ✓ read {name} = {}",
                    path_text(path),
                    quote(&words, 60)
                ));
                self.vars.insert(name, json!(words));
            }
            "if" => {
                let entering = self
                    .resume
                    .as_ref()
                    .is_some_and(|r| r.starts_with(path) && r.len() > path.len());
                let obs = self.screen(!entering).await.map_err(err)?;
                let yes = holds(&obs, &self.vars, &step["cond"]);
                let branch = if yes {
                    step.get("then")
                } else {
                    step.get("else")
                };
                if !entering {
                    self.lines.push(format!(
                        "{}. {} → {}",
                        path_text(path),
                        label(step),
                        if yes { "then" } else { "else" }
                    ));
                }
                if let Some(Value::Array(b)) = branch {
                    Box::pin(self.block(b, path)).await?;
                }
            }
            "for_each" => {
                let items = step["items"].as_array().cloned().unwrap_or_default();
                let name = s("as");
                let body = step["steps"].as_array().cloned().unwrap_or_default();
                let saved = self.vars.get(&name).cloned();
                for (i, item) in items.iter().enumerate() {
                    let mut at = path.to_vec();
                    at.push(i + 1);
                    if let Some(r) = &self.resume
                        && !r.starts_with(&at)
                        && at.as_slice() < r.as_slice()
                    {
                        continue;
                    }
                    self.vars.insert(name.clone(), item.clone());
                    Box::pin(self.block(&body, &at)).await?;
                }
                match saved {
                    Some(v) => self.vars.insert(name, v),
                    None => self.vars.remove(&name),
                };
                self.done(path, format!("for each of {} items", items.len()));
            }
            "call" => {
                let route = s("route");
                let args = step.get("args").cloned().unwrap_or(json!({}));
                let a = |k: &str| args.get(k).cloned().unwrap_or(Value::Null);
                let (tool_name, tool_args) = match route.as_str() {
                    "media" => (
                        "phone_control",
                        json!({ "control": "media", "action": a("action") }),
                    ),
                    "volume" => (
                        "phone_control",
                        json!({ "control": "volume", "stream": args.get("stream").cloned().unwrap_or(json!("media")), "percent": a("percent") }),
                    ),
                    "flashlight" => (
                        "phone_control",
                        json!({ "control": "flashlight", "on": a("on") }),
                    ),
                    "brightness" => (
                        "phone_control",
                        json!({ "control": "brightness", "percent": a("percent") }),
                    ),
                    "ringer" => (
                        "phone_control",
                        json!({ "control": "ringer", "mode": a("mode") }),
                    ),
                    "do_not_disturb" => (
                        "phone_control",
                        json!({ "control": "do_not_disturb", "on": a("on") }),
                    ),
                    "panel" => (
                        "phone_control",
                        json!({ "control": "panel", "panel": a("panel") }),
                    ),
                    "link" => ("open_link", json!({ "url": a("url"), "package": a("app") })),
                    "clipboard" => ("set_clipboard", json!({ "text": a("text") })),
                    "timer" => {
                        let n = |k: &str| args.get(k).and_then(Value::as_i64).unwrap_or(0);
                        (
                            "phone_control",
                            json!({ "control": "timer", "duration_s": n("minutes") * 60 + n("seconds") }),
                        )
                    }
                    "alarm" => (
                        "phone_control",
                        json!({ "control": "alarm", "hour": a("hour"), "minute": a("minute") }),
                    ),
                    _ => {
                        return Err(fail(
                            "route_unavailable",
                            format!("no route {}", quote(&route, 30)),
                        ));
                    }
                };
                self.routes.push(route.clone());
                self.act(tool_name, tool_args).await.map_err(err)?;
                self.done(path, format!("call {route} (route: {route})"));
            }
            "ask_owner" => {
                let v = self
                    .act("ask_owner", json!({ "message": s("message") }))
                    .await
                    .map_err(err)?;
                let said = first_text(&v);
                if !said.starts_with("The owner says it's done.") {
                    let end = said.find(" The screen after").unwrap_or(said.len());
                    return Err(Stop::Owner(path_text(path), said[..end].trim().to_owned()));
                }
                self.done(path, format!("{} → the owner tapped Done", label(step)));
            }
            _ => return Err(fail("bad_value", "unknown step".into())),
        }
        Ok(())
    }
}

fn with_progress(stop: Stop, rep: i64, times: i64) -> Stop {
    match stop {
        Stop::Failed {
            path,
            command,
            selector,
            reason,
            message,
            candidates,
            tried,
        } if rep > 0 => Stop::Failed {
            path,
            command,
            selector,
            reason,
            message: format!("{message} (after {rep} of {times} taps)"),
            candidates,
            tried,
        },
        other => other,
    }
}

/// A step with every word filled from vars; nested blocks fill later, item by item.
fn fill_step(step: &Value, vars: &Map<String, Value>) -> Result<Value, String> {
    let mut out = Map::new();
    for (k, v) in step.as_object().cloned().unwrap_or_default() {
        let filled = match k.as_str() {
            "then" | "else" | "steps" | "note" | "do" => v,
            _ => fill_value(&v, vars)?,
        };
        out.insert(k, filled);
    }
    Ok(Value::Object(out))
}

/// The element that holds the on/off state for [id]: itself, inside it, or on its row.
fn switch_for(nodes: &[UiNode], id: &str) -> Option<UiNode> {
    let node = nodes.iter().find(|n| n.id == id)?;
    if node.checked.is_some() {
        return Some(node.clone());
    }
    let row = node.bounds;
    nodes
        .iter()
        .filter(|n| n.checked.is_some() && usable(n))
        .filter(|n| {
            let (_, cy) = n.bounds.center();
            n.parent.as_deref() == Some(id) || (cy >= row.top && cy <= row.bottom)
        })
        .min_by_key(|n| {
            let (cx, cy) = n.bounds.center();
            let (rx, ry) = row.center();
            (cy - ry).abs() + (cx - rx).abs()
        })
        .cloned()
}

/// The package for an app name from list_apps' answer; Err holds the candidates.
fn pick_app(listing: &str, name: &str) -> Result<String, Vec<String>> {
    let apps: Vec<(String, String)> = listing
        .lines()
        .filter_map(|l| l.strip_prefix("- "))
        .filter_map(|l| {
            let (pkg, rest) = l.split_once(' ')?;
            Some((pkg.to_owned(), rest.trim().trim_matches('"').to_owned()))
        })
        .collect();
    let wanted = norm(name);
    let exact: Vec<_> = apps
        .iter()
        .filter(|(_, label)| norm(label) == wanted)
        .collect();
    match (exact.as_slice(), apps.as_slice()) {
        ([one], _) => Ok(one.0.clone()),
        ([], [one]) => Ok(one.0.clone()),
        (_, _) => Err(apps
            .iter()
            .take(MAX_CANDIDATES)
            .map(|(p, l)| format!("{p} {}", quote(l, 40)))
            .collect()),
    }
}

/// One line describing a step, for the AI.
fn label(step: &Value) -> String {
    let s = |k: &str| step.get(k).and_then(Value::as_str).unwrap_or_default();
    let sel = |k: &str| step.get(k).map(selector_text).unwrap_or_default();
    match s("do") {
        "tap" => {
            let mut t = format!(
                "{} {}",
                if step["long"] == json!(true) {
                    "long-press"
                } else {
                    "tap"
                },
                sel("sel")
            );
            match &step["repeat"] {
                Value::Null => {}
                v if *v == json!(1) => {}
                v => t.push_str(&format!(" ×{}", scalar_text(v))),
            }
            t
        }
        "type" => {
            let mut t = format!("type {}", quote(s("text"), 60));
            if step.get("sel").is_some() {
                t.push_str(&format!(" into {}", sel("sel")));
            }
            if step["submit"] == json!(true) {
                t.push_str(" and submit");
            }
            t
        }
        "find" => format!("find {}", sel("sel")),
        "toggle" => format!(
            "turn {} {}",
            sel("sel"),
            if step["on"] == json!(true) {
                "on"
            } else {
                "off"
            }
        ),
        "scroll" => match step.get("until") {
            Some(u) => format!("scroll {} to {}", s("direction"), selector_text(u)),
            None => format!("scroll {}", s("direction")),
        },
        "wait" => match step.get("sel") {
            Some(_) if step["gone"] == json!(true) => format!("wait until {} is gone", sel("sel")),
            Some(_) => format!("wait for {}", sel("sel")),
            None => "wait for the screen to settle".into(),
        },
        "expect" => format!("expect {}", step["cond"]),
        "if" => format!("if {}", step["cond"]),
        "ask_owner" => format!("ask the owner: {}", quote(s("message"), 80)),
        other => other.to_owned(),
    }
}

fn selector_text(sel: &Value) -> String {
    match sel.as_object() {
        Some(o) if o.len() == 1 && o.contains_key("text") => {
            quote(o["text"].as_str().unwrap_or_default(), 60)
        }
        _ => sel.to_string(),
    }
}

/// run_program: validates, runs, and answers with what happened and the screen now.
pub async fn run(
    state: &Arc<AppState>,
    args: &Value,
    device_id: &str,
) -> Result<Value, ProtocolError> {
    let bad = |m: String| ProtocolError::new(ErrorCode::InvalidRequest, m);
    let steps = validate(args.get("steps").unwrap_or(&Value::Null)).map_err(bad)?;
    let resume = match args.get("from_step") {
        None | Some(Value::Null) => None,
        Some(Value::String(s)) => {
            Some(parse_path(s).ok_or_else(|| {
                bad("from_step is a step number such as \"4\" or \"3.2.1\"".into())
            })?)
        }
        Some(Value::Number(n)) => Some(vec![
            n.as_u64()
                .filter(|n| *n >= 1)
                .ok_or_else(|| bad("from_step must be 1 or more".into()))? as usize,
        ]),
        Some(_) => {
            return Err(bad(
                "from_step is a step number such as \"4\" or \"3.2.1\"".into()
            ));
        }
    };
    let vars = match args.get("vars") {
        None | Some(Value::Null) => Map::new(),
        Some(Value::Object(m)) if m.len() <= 40 => m.clone(),
        Some(_) => return Err(bad("vars must be an object of at most 40 values".into())),
    };
    let mut runner = Runner {
        state,
        device: device_id.to_owned(),
        vars,
        actions: 0,
        started: Instant::now(),
        lines: Vec::new(),
        last: None,
        resume,
        routes: Vec::new(),
    };
    let outcome = runner
        .block(steps.as_array().expect("validated list"), &[])
        .await;
    let ms = runner.started.elapsed().as_millis() as u64;
    let mut report = json!({
        "status": "done",
        "actions": runner.actions,
        "ms": ms,
    });
    if !runner.vars.is_empty() {
        report["vars"] = Value::Object(runner.vars.clone());
    }
    if !runner.routes.is_empty() {
        report["routes"] = json!(runner.routes);
    }
    let head = match &outcome {
        Ok(()) => "Ran the program: every step done. Check the screen below before telling the user it worked.".to_owned(),
        Err(Stop::Failed { path, command, selector, reason, message, candidates, tried }) => {
            report["status"] = json!("failed");
            report["step"] = json!(path);
            report["command"] = json!(command);
            if let Some(s) = selector {
                report["selector"] = s.clone();
            }
            report["reason"] = json!(reason);
            if !candidates.is_empty() {
                report["candidates"] = json!(candidates);
            }
            report["recovery_tried"] = json!(tried);
            report["resume"] = json!({ "from_step": path });
            format!(
                "The program stopped at step {path} ({command}): {reason}, {message}. Fix only that step (or do it with the \
                 normal tools), then call run_program again with the same steps, from_step \"{path}\", and the vars below; \
                 finished steps do not run again."
            )
        }
        Err(Stop::Error(path, command, e)) => {
            report["status"] = json!("failed");
            report["step"] = json!(path);
            report["command"] = json!(command);
            report["reason"] = json!(e.code.as_str());
            report["resume"] = json!({ "from_step": path });
            format!(
                "The program stopped at step {path} ({command}): {}: {}. Tell the user; if they want to continue, fix the \
                 cause and call run_program with from_step \"{path}\".",
                e.code, e.message
            )
        }
        Err(Stop::Owner(path, said)) => {
            report["status"] = json!("owner");
            report["step"] = json!(path);
            let next = parse_path(path).map(|p| path_text(&next_path(&p))).unwrap_or_default();
            report["resume"] = json!({ "from_step": next });
            format!("The program stopped at step {path}: {said} If the user wants to continue later, call run_program with from_step \"{next}\".")
        }
        Err(Stop::Paused(path)) => {
            report["status"] = json!("paused");
            report["resume"] = json!({ "from_step": path });
            format!("The program paused before step {path} to keep this call short. Call run_program with from_step \"{path}\" and the vars below to continue.")
        }
    };
    if outcome.is_err() && !runner.vars.is_empty() {
        report["resume"]["vars"] = Value::Object(runner.vars.clone());
    }
    let mut text = format!(
        "{head}\n{}",
        serde_json::to_string(&report).unwrap_or_default()
    );
    if !runner.lines.is_empty() {
        text.push_str("\nDone in this call:\n");
        text.push_str(&runner.lines.join("\n"));
    }
    let failed_here = matches!(outcome, Err(Stop::Failed { .. }));
    let screen = match runner.last.take() {
        Some(v) if !failed_here => Some(v),
        _ => Box::pin(super::run_tool_logged(
            state,
            "observe",
            &json!({ "device_id": device_id, "screenshot": false }),
        ))
        .await
        .ok(),
    };
    let mut result = text_result(text);
    if let Some(screen) = screen
        && let (Some(Value::Array(content)), Some(Value::Array(parts))) =
            (result.get_mut("content"), screen.get("content"))
    {
        content.extend(parts.iter().filter(|p| !first_is_headline(p)).cloned());
    }
    Ok(result)
}
