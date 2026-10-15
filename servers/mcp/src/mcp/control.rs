// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! The `phone_control` tool (protocol 1.11): volume, media keys, brightness,
//! the Wi-Fi and Bluetooth panels, and the flashlight, each behind its own
//! capability. The texts here match `servers/vercel/src/control.ts` word for
//! word; `packages/schemas/v1/mcp/control-*.json` pins both.

use latch_protocol::{
    ControlReading, MediaAction, PhoneControl, SettingsPanel, VolumeStep, VolumeStream,
};
use serde_json::{Value, json};

use super::{device_id_schema, screenshot_after_schema, tool};

pub const NAME: &str = "phone_control";

pub fn definition() -> Value {
    tool(
        NAME,
        "Change a phone setting",
        "Change the volume, play or pause media, set the screen brightness, switch the \
         flashlight, or open the Wi-Fi or Bluetooth panel. Each needs its own switch, which the \
         owner turns on in Latch. volume: a stream and either percent (0-100) or step (up, \
         down, mute, unmute). media: action. brightness: percent, or auto true/false. \
         flashlight: on. panel: wifi or bluetooth; Android does not let apps flip those \
         switches, so the panel opens on screen and you tap the switch in it (the answer is the \
         new screen).",
        false,
        json!({
            "device_id": device_id_schema(),
            "control": { "type": "string", "enum": ["volume", "media", "brightness", "flashlight", "panel"] },
            "stream": { "type": "string", "enum": ["media", "ring", "notification", "alarm"], "description": "volume: which volume. Default media." },
            "percent": { "type": "integer", "minimum": 0, "maximum": 100, "description": "volume or brightness: the new level." },
            "step": { "type": "string", "enum": ["up", "down", "mute", "unmute"], "description": "volume: instead of percent." },
            "action": { "type": "string", "enum": ["play_pause", "play", "pause", "next", "previous", "stop"], "description": "media: the key to press." },
            "auto": { "type": "boolean", "description": "brightness: automatic brightness on or off, instead of percent." },
            "on": { "type": "boolean", "description": "flashlight: on or off." },
            "panel": { "type": "string", "enum": ["wifi", "bluetooth"], "description": "panel: which one to open." },
            "screenshot_after": screenshot_after_schema(),
        }),
        &["control"],
    )
}

/// Arguments each control accepts besides `device_id`, `control`, and `screenshot_after`.
fn allowed(control: &str) -> &'static [&'static str] {
    match control {
        "volume" => &["stream", "percent", "step"],
        "media" => &["action"],
        "brightness" => &["percent", "auto"],
        "flashlight" => &["on"],
        "panel" => &["panel"],
        _ => &[],
    }
}

const PARAMS: [&str; 7] = ["stream", "percent", "step", "action", "auto", "on", "panel"];

fn string<'a>(args: &'a Value, key: &str) -> Result<Option<&'a str>, String> {
    match args.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(s)) => Ok(Some(s)),
        Some(_) => Err(format!("{key} must be a string")),
    }
}

fn pick<T: Copy>(args: &Value, key: &str, options: &[(&str, T)]) -> Result<Option<T>, String> {
    let Some(value) = string(args, key)? else {
        return Ok(None);
    };
    options
        .iter()
        .find(|(name, _)| *name == value)
        .map(|(_, v)| Some(*v))
        .ok_or_else(|| {
            let names: Vec<&str> = options.iter().map(|(n, _)| *n).collect();
            format!("{key} must be one of {}", names.join(", "))
        })
}

fn boolean(args: &Value, key: &str) -> Result<Option<bool>, String> {
    match args.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Bool(b)) => Ok(Some(*b)),
        Some(_) => Err(format!("{key} must be true or false")),
    }
}

fn percent(args: &Value) -> Result<Option<u8>, String> {
    match args.get("percent") {
        None | Some(Value::Null) => Ok(None),
        Some(v) => v
            .as_u64()
            .filter(|p| *p <= 100)
            .map(|p| Some(p as u8))
            .ok_or_else(|| "percent must be an integer from 0 to 100".into()),
    }
}

/// The control the tool arguments ask for, or the reason they are wrong.
pub fn parse(args: &Value) -> Result<PhoneControl, String> {
    let control = string(args, "control")?.ok_or("control is required")?;
    let accepted = allowed(control);
    if accepted.is_empty() {
        return Err("control must be one of volume, media, brightness, flashlight, panel".into());
    }
    if let Some(extra) = PARAMS
        .iter()
        .find(|k| !accepted.contains(k) && args.get(**k).is_some_and(|v| !v.is_null()))
    {
        return Err(format!("{control} does not take {extra}"));
    }
    match control {
        "volume" => {
            let stream = pick(
                args,
                "stream",
                &[
                    ("media", VolumeStream::Media),
                    ("ring", VolumeStream::Ring),
                    ("notification", VolumeStream::Notification),
                    ("alarm", VolumeStream::Alarm),
                ],
            )?
            .unwrap_or(VolumeStream::Media);
            let percent = percent(args)?;
            let step = pick(
                args,
                "step",
                &[
                    ("up", VolumeStep::Up),
                    ("down", VolumeStep::Down),
                    ("mute", VolumeStep::Mute),
                    ("unmute", VolumeStep::Unmute),
                ],
            )?;
            if percent.is_some() == step.is_some() {
                return Err("volume takes either percent or step".into());
            }
            Ok(PhoneControl::Volume {
                stream,
                percent,
                step,
            })
        }
        "media" => {
            let action = pick(
                args,
                "action",
                &[
                    ("play_pause", MediaAction::PlayPause),
                    ("play", MediaAction::Play),
                    ("pause", MediaAction::Pause),
                    ("next", MediaAction::Next),
                    ("previous", MediaAction::Previous),
                    ("stop", MediaAction::Stop),
                ],
            )?
            .ok_or("media needs action")?;
            Ok(PhoneControl::Media { action })
        }
        "brightness" => {
            let percent = percent(args)?;
            let auto = boolean(args, "auto")?;
            if percent.is_some() == auto.is_some() {
                return Err("brightness takes either percent or auto".into());
            }
            Ok(PhoneControl::Brightness { percent, auto })
        }
        "flashlight" => Ok(PhoneControl::Flashlight {
            on: boolean(args, "on")?.ok_or("flashlight needs on")?,
        }),
        _ => {
            let panel = pick(
                args,
                "panel",
                &[
                    ("wifi", SettingsPanel::Wifi),
                    ("bluetooth", SettingsPanel::Bluetooth),
                ],
            )?
            .ok_or("panel needs panel")?;
            Ok(PhoneControl::Panel { panel })
        }
    }
}

/// The tool's answer after a control that stays off screen ran.
pub fn answer(control: &PhoneControl, reading: Option<&ControlReading>) -> String {
    let words = control.describe();
    let mut chars = words.chars();
    let first = chars
        .next()
        .map(|c| c.to_lowercase().to_string())
        .unwrap_or_default();
    let done = format!("Done: {first}{}.", chars.as_str());
    let now = reading.and_then(|r| match control {
        PhoneControl::Volume { .. } => match (r.on, r.percent) {
            (Some(true), _) => Some("It is muted now.".to_owned()),
            (_, Some(p)) => Some(format!("It is at {p}% now.")),
            _ => None,
        },
        PhoneControl::Brightness { .. } => match (r.on, r.percent) {
            (Some(true), _) => Some("Automatic brightness is on.".to_owned()),
            (_, Some(p)) => Some(format!("Brightness is at {p}% now.")),
            _ => None,
        },
        PhoneControl::Flashlight { .. } => {
            r.on.map(|on| format!("The flashlight is {} now.", if on { "on" } else { "off" }))
        }
        _ => None,
    });
    match (control, now) {
        (_, Some(now)) => format!("{done} {now}"),
        (PhoneControl::Media { .. }, None) => {
            format!("{done} The key went to whichever app is playing; observe to check it.")
        }
        (_, None) => done,
    }
}
