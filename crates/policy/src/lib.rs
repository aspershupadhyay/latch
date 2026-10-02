//! Authorization for device commands.
//!
//! [`evaluate`] is a pure function: the same inputs always give the same
//! decision, and it performs no I/O. The gateway calls it before any command
//! leaves for a device; the device then re-checks what only it can know
//! (platform permission, live element state, sensitive-field flags).
//!
//! Screen content is untrusted. It is only ever used here to *raise* the
//! level of scrutiny (ask the owner, refuse a sensitive field), never to
//! lower it.

use latch_protocol::{
    CapabilityState, CapabilityStatus, Command, ConfirmRequest, ErrorCode, Observation,
    ProtocolError, RiskLevel, SessionInfo, Target, UiNode, validate,
};

/// Actions must be planned against an observation at most this old.
pub const MAX_OBSERVATION_AGE_MS: u64 = 60_000;

/// What the gateway knows about a device at decision time.
#[derive(Debug, Clone, Copy)]
pub struct DeviceContext<'a> {
    pub capabilities: &'a [CapabilityState],
    pub session: SessionInfo,
    /// The most recent observation this device returned, and when it arrived
    /// (gateway clock, Unix ms).
    pub latest_observation: Option<(&'a Observation, u64)>,
    /// Gateway clock, Unix ms.
    pub now_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Decision {
    /// Send the command as is.
    Allow { risk: RiskLevel },
    /// Send the command with an approval request the owner must accept on the phone.
    Confirm(ConfirmRequest),
    /// Refuse without contacting the device.
    Deny(ProtocolError),
}

fn deny(code: ErrorCode, message: impl Into<String>) -> Decision {
    Decision::Deny(ProtocolError::new(code, message))
}

pub fn evaluate(command: &Command, ctx: &DeviceContext<'_>) -> Decision {
    if let Err(error) = validate::command(command) {
        return Decision::Deny(error);
    }
    if ctx.session.paused {
        return deny(
            ErrorCode::DeviceUnavailable,
            "the owner paused this session",
        );
    }
    if ctx.session.expires_at_ms <= ctx.now_ms {
        return deny(
            ErrorCode::DeviceUnavailable,
            "the session on the phone has ended",
        );
    }
    for capability in command.required_capabilities() {
        let status = ctx
            .capabilities
            .iter()
            .find(|c| c.capability == capability)
            .map(|c| c.status);
        match status {
            Some(CapabilityStatus::Enabled) => {}
            Some(CapabilityStatus::Disabled) => {
                return deny(
                    ErrorCode::PermissionMissing,
                    format!(
                        "the owner has not allowed '{}' ({})",
                        capability.id(),
                        capability.describe()
                    ),
                );
            }
            Some(CapabilityStatus::NeedsPermission) => {
                return deny(
                    ErrorCode::PermissionMissing,
                    format!(
                        "'{}' needs an Android or iOS permission the owner has not granted",
                        capability.id()
                    ),
                );
            }
            Some(CapabilityStatus::Unsupported) | None => {
                return deny(
                    ErrorCode::UnsupportedCapability,
                    format!("this device does not support '{}'", capability.id()),
                );
            }
        }
    }

    let observation = match command.observation_id() {
        None => None,
        Some(id) => match ctx.latest_observation {
            Some((obs, received_at)) if obs.observation_id == id => {
                if ctx.now_ms.saturating_sub(received_at) > MAX_OBSERVATION_AGE_MS {
                    return deny(
                        ErrorCode::StaleObservation,
                        "that observation is more than 60 seconds old",
                    );
                }
                Some(obs)
            }
            Some(_) => {
                return deny(
                    ErrorCode::StaleObservation,
                    "that is not the latest observation of this device",
                );
            }
            None => {
                return deny(
                    ErrorCode::StaleObservation,
                    "observe the screen before acting on it",
                );
            }
        },
    };

    let assessment = match assess(command, observation) {
        Ok(a) => a,
        Err(error) => return Decision::Deny(error),
    };

    let needs_approval = assessment.risk == RiskLevel::High
        || (ctx.session.approve_every_action && command.is_action());
    if needs_approval {
        Decision::Confirm(ConfirmRequest {
            title: assessment.title,
            detail: assessment.detail,
            risk: assessment.risk,
            remember: assessment.remember,
        })
    } else {
        Decision::Allow {
            risk: assessment.risk,
        }
    }
}

struct Assessment {
    risk: RiskLevel,
    title: String,
    detail: String,
    /// Key under which the owner may save "this session" or "always" answers.
    remember: Option<String>,
}

const AGENT_DETAIL: &str = "Requested by an AI agent connected through Latch.";
const CONSEQUENTIAL_DETAIL: &str = "Requested by an AI agent connected through Latch. This control may send, call, post, delete, or change something that is hard to undo.";
const CRITICAL_DETAIL: &str = "Requested by an AI agent connected through Latch. This involves money, app installs, permissions, or account deletion, so Latch asks every time.";

/// How much human attention an action needs, judged from its labels and app.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Consequence {
    /// No sign of an effect beyond the screen.
    None,
    /// Sends, calls, posts, deletes, or similar: the owner approves, and may
    /// save that answer for the session or for this app.
    Consequential,
    /// Money, installs, permissions, account deletion: approved every time.
    Critical,
}

impl Assessment {
    fn medium(title: String) -> Self {
        Assessment {
            risk: RiskLevel::Medium,
            title,
            detail: AGENT_DETAIL.into(),
            remember: None,
        }
    }

    /// `key` identifies the action for saved answers, e.g. `tap|com.example|send`.
    fn judged(consequence: Consequence, title: String, key: String) -> Self {
        match consequence {
            Consequence::None => Assessment::medium(title),
            Consequence::Consequential => Assessment {
                risk: RiskLevel::High,
                title,
                detail: CONSEQUENTIAL_DETAIL.into(),
                remember: Some(
                    key.chars()
                        .take(latch_protocol::MAX_REMEMBER_CHARS)
                        .collect(),
                ),
            },
            Consequence::Critical => Assessment {
                risk: RiskLevel::High,
                title,
                detail: CRITICAL_DETAIL.into(),
                remember: None,
            },
        }
    }
}

fn assess(
    command: &Command,
    observation: Option<&Observation>,
) -> Result<Assessment, ProtocolError> {
    let low = |title: String| Assessment {
        risk: RiskLevel::Low,
        title,
        detail: AGENT_DETAIL.into(),
        remember: None,
    };
    match command {
        Command::DeviceInfo {} => Ok(low("Read device information".into())),
        Command::Observe {
            include_screenshot, ..
        } => Ok(low(if *include_screenshot {
            "Read the screen and take a screenshot".into()
        } else {
            "Read the screen".into()
        })),
        Command::ListApps {} => Ok(low("List installed apps".into())),
        Command::Global { action } => Ok(low(format!("Press {action:?}"))),
        Command::LaunchApp { package } => Ok(Assessment::medium(format!("Open {package}"))),
        Command::Swipe { from, to, .. } => {
            let obs = observation.ok_or_else(internal_missing_observation)?;
            for (x, y) in [(from.x, from.y), (to.x, to.y)] {
                check_on_screen(obs, x, y)?;
            }
            if let Some(node) = obs.node_at(from.x, from.y)
                && node.sensitive
            {
                return Err(sensitive());
            }
            // In a phone app a swipe can answer, decline, or place a call.
            let package = obs.package.as_deref();
            let consequence = if package.is_some_and(is_call_package) {
                Consequence::Consequential
            } else {
                Consequence::None
            };
            Ok(Assessment::judged(
                consequence,
                format!("Swipe on the screen{}", place(package)),
                format!("swipe|{}|", package.unwrap_or("?")),
            ))
        }
        Command::Tap {
            target, long_press, ..
        } => {
            let obs = observation.ok_or_else(internal_missing_observation)?;
            let verb = if *long_press { "Long-press" } else { "Tap" };
            let node = match target {
                Target::Element { element } => Some(resolve(obs, element)?),
                Target::Point { x, y } => {
                    check_on_screen(obs, *x, *y)?;
                    obs.node_at(*x, *y)
                }
            };
            let package = obs.package.as_deref();
            let Some(node) = node else {
                return Ok(Assessment::judged(
                    classify(&[], package),
                    format!("{verb} on the screen{}", place(package)),
                    format!("{}|{}|", verb.to_lowercase(), package.unwrap_or("?")),
                ));
            };
            if node.sensitive {
                return Err(sensitive());
            }
            let scope = scope_of(obs, node);
            let label = scope
                .iter()
                .find_map(|n| own_label(n))
                .map_or_else(|| label_of(node), shorten);
            Ok(Assessment::judged(
                classify(&scope, package),
                format!("{verb} “{label}”{}", place(package)),
                format!(
                    "{}|{}|{}",
                    verb.to_lowercase(),
                    package.unwrap_or("?"),
                    label.to_lowercase()
                ),
            ))
        }
        Command::TypeText {
            element,
            text,
            submit,
            ..
        } => {
            let obs = observation.ok_or_else(internal_missing_observation)?;
            let node = resolve(obs, element)?;
            if node.sensitive || looks_like_secret_field(node) {
                return Err(sensitive());
            }
            if !node.editable {
                return Err(ProtocolError::new(
                    ErrorCode::InvalidRequest,
                    "that element is not an editable text field",
                ));
            }
            let count = text.chars().count();
            if !*submit {
                return Ok(Assessment::medium(format!(
                    "Type {count} characters into “{}”",
                    label_of(node)
                )));
            }
            // Enter in a chat box sends; in a search box it searches. The field's
            // own text is what is being typed, so it is never used as its name.
            let package = obs.package.as_deref();
            let name = field_label(node);
            let consequence = match classify(&[node], package) {
                Consequence::None if is_search_field(node) => Consequence::None,
                Consequence::None => Consequence::Consequential,
                raised => raised,
            };
            Ok(Assessment::judged(
                consequence,
                format!(
                    "Type {count} characters into “{name}” and press Enter{}",
                    place(package)
                ),
                format!("enter|{}|{}", package.unwrap_or("?"), name.to_lowercase()),
            ))
        }
        Command::WaitFor { text, gone, .. } => Ok(low(format!(
            "Wait for “{}” to {}",
            shorten(text),
            if *gone { "disappear" } else { "appear" }
        ))),
        Command::ScrollTo {
            text, container, ..
        } => {
            let obs = observation.ok_or_else(internal_missing_observation)?;
            if let Some(container) = container
                && resolve(obs, container)?.sensitive
            {
                return Err(sensitive());
            }
            Ok(Assessment::medium(format!(
                "Scroll to “{}”{}",
                shorten(text),
                place(obs.package.as_deref())
            )))
        }
    }
}

fn place(package: Option<&str>) -> String {
    package.map(|p| format!(" in {p}")).unwrap_or_default()
}

/// Most descendants of a tapped element that are read for its meaning.
const MAX_SCOPE_NODES: usize = 64;

/// The elements that say what a tap on `node` does: the node itself, then
/// what is drawn inside it (an unlabeled button often holds a labeled icon),
/// and, when none of those has a label, the nearest labeled ancestor.
pub fn scope_of<'a>(obs: &'a Observation, node: &'a UiNode) -> Vec<&'a UiNode> {
    let mut scope = vec![node];
    let mut frontier = vec![node.id.as_str()];
    while let Some(parent) = frontier.pop() {
        for child in obs
            .nodes
            .iter()
            .filter(|n| n.parent.as_deref() == Some(parent))
        {
            if scope.len() >= MAX_SCOPE_NODES {
                break;
            }
            // Guard against malformed trees that loop back to an included node.
            if scope.iter().any(|s| s.id == child.id) {
                continue;
            }
            scope.push(child);
            frontier.push(child.id.as_str());
        }
    }
    if scope.iter().all(|n| own_label(n).is_none()) {
        let mut cursor = node.parent.as_deref();
        for _ in 0..3 {
            let Some(parent) = cursor.and_then(|id| obs.node(id)) else {
                break;
            };
            if own_label(parent).is_some() {
                scope.push(parent);
                break;
            }
            cursor = parent.parent.as_deref();
        }
    }
    scope
}

/// Judges the elements a tap acts on, in the app that shows them.
/// Screen text only ever raises the outcome; it can never lower it.
pub fn classify(scope: &[&UiNode], package: Option<&str>) -> Consequence {
    let package_critical = package.is_some_and(|p| CRITICAL_PACKAGES.contains(&p));
    if package_critical
        || scope
            .iter()
            .any(|n| matches(n, CRITICAL_WORDS, CRITICAL_PHRASES))
    {
        return Consequence::Critical;
    }
    if package.is_some_and(is_call_package)
        || scope
            .iter()
            .any(|n| is_consequential(n) || shows_phone_number(n))
    {
        return Consequence::Consequential;
    }
    Consequence::None
}

fn is_call_package(package: &str) -> bool {
    CALL_PACKAGES.contains(&package)
}

/// Tapping a phone number usually starts a call.
pub fn shows_phone_number(node: &UiNode) -> bool {
    [node.text.as_deref(), node.description.as_deref()]
        .into_iter()
        .flatten()
        .any(|t| {
            let t = t.trim();
            let digits = t.chars().filter(char::is_ascii_digit).count();
            (7..=15).contains(&digits)
                && t.chars()
                    .all(|c| c.is_ascii_digit() || " +-().\u{a0}".contains(c))
        })
}

/// A text field's name for prompts: its description or resource id, never its
/// content (which is what the agent is typing).
fn field_label(node: &UiNode) -> String {
    let raw = node
        .description
        .as_deref()
        .filter(|t| !t.trim().is_empty())
        .or_else(|| {
            node.resource_id
                .as_deref()
                .map(|r| r.rsplit('/').next().unwrap_or(r))
        })
        .unwrap_or(&node.role);
    shorten(raw)
}

/// Search, address, and URL boxes, where Enter only looks something up.
pub fn is_search_field(node: &UiNode) -> bool {
    [node.description.as_deref(), node.resource_id.as_deref()]
        .into_iter()
        .flatten()
        .any(|field| {
            let ws = words(field);
            let compact = ws.concat();
            ws.iter().any(|w| SEARCH_FIELD_WORDS.contains(&w.as_str()))
                || SEARCH_FIELD_WORDS
                    .iter()
                    .any(|w| w.len() >= 5 && compact.contains(w))
        })
}

fn own_label(node: &UiNode) -> Option<&str> {
    node.text
        .as_deref()
        .filter(|t| !t.trim().is_empty())
        .or(node.description.as_deref().filter(|t| !t.trim().is_empty()))
}

fn internal_missing_observation() -> ProtocolError {
    ProtocolError::new(
        ErrorCode::Internal,
        "action evaluated without an observation",
    )
}

fn sensitive() -> ProtocolError {
    ProtocolError::new(
        ErrorCode::SensitiveTarget,
        "that element is a password, PIN, one-time code, or payment field",
    )
}

fn resolve<'a>(obs: &'a Observation, element: &str) -> Result<&'a UiNode, ProtocolError> {
    let node = obs.node(element).ok_or_else(|| {
        ProtocolError::new(
            ErrorCode::TargetNotFound,
            "no element with that id in the observation",
        )
    })?;
    if !node.enabled {
        return Err(ProtocolError::new(
            ErrorCode::TargetNotFound,
            "that element is disabled",
        ));
    }
    if node.bounds.is_empty() {
        return Err(ProtocolError::new(
            ErrorCode::TargetNotFound,
            "that element is not visible",
        ));
    }
    Ok(node)
}

fn check_on_screen(obs: &Observation, x: i32, y: i32) -> Result<(), ProtocolError> {
    let inside = x >= 0
        && y >= 0
        && (x as i64) < i64::from(obs.screen.width)
        && (y as i64) < i64::from(obs.screen.height);
    if inside {
        Ok(())
    } else {
        Err(ProtocolError::new(
            ErrorCode::InvalidRequest,
            format!(
                "point ({x}, {y}) is outside the {}x{} screen",
                obs.screen.width, obs.screen.height
            ),
        ))
    }
}

/// Short human label for an element, for approval prompts. Untrusted text.
fn label_of(node: &UiNode) -> String {
    let raw = node
        .text
        .as_deref()
        .filter(|t| !t.trim().is_empty())
        .or(node.description.as_deref().filter(|t| !t.trim().is_empty()))
        .or(node.resource_id.as_deref())
        .unwrap_or(&node.role);
    shorten(raw)
}

/// One line of at most 48 characters, for approval prompts.
fn shorten(raw: &str) -> String {
    let single_line: String = raw.split_whitespace().collect::<Vec<_>>().join(" ");
    let mut label: String = single_line.chars().take(48).collect();
    if single_line.chars().count() > 48 {
        label.push('…');
    }
    label
}

/// Words that mark a control whose effect leaves the phone or is hard to undo.
const CONSEQUENTIAL_WORDS: &[&str] = &[
    "send",
    "pay",
    "buy",
    "purchase",
    "order",
    "checkout",
    "delete",
    "remove",
    "erase",
    "confirm",
    "submit",
    "post",
    "publish",
    "share",
    "transfer",
    "install",
    "uninstall",
    "accept",
    "agree",
    "allow",
    "grant",
    "subscribe",
    "unsubscribe",
    "book",
    "reserve",
    "call",
    "dial",
    "sim",
    "block",
    "report",
    "reset",
    "wipe",
    "logout",
    "signout",
    "donate",
    "withdraw",
    "deposit",
    "approve",
    "upload",
    "forward",
    "reply",
    "tweet",
    "retweet",
    "unfollow",
    "unfriend",
    "archive",
    "trash",
    "discard",
    "format",
    "enviar",
    "pagar",
    "comprar",
    "eliminar",
    "borrar",
    "senden",
    "kaufen",
    "löschen",
    "envoyer",
    "payer",
    "acheter",
    "supprimer",
];

const CONSEQUENTIAL_PHRASES: &[&str] = &[
    "sign out",
    "log out",
    "place order",
    "pay now",
    "buy now",
    "factory reset",
    "send money",
    "confirm payment",
    "delete account",
    "close account",
    "make payment",
];

/// Words that mark money, installs, or account deletion: asked every time.
const CRITICAL_WORDS: &[&str] = &[
    "pay",
    "buy",
    "purchase",
    "checkout",
    "transfer",
    "withdraw",
    "deposit",
    "donate",
    "install",
    "uninstall",
    "wipe",
    "pagar",
    "comprar",
    "kaufen",
    "payer",
    "acheter",
];

const CRITICAL_PHRASES: &[&str] = &[
    "place order",
    "pay now",
    "buy now",
    "factory reset",
    "send money",
    "add money",
    "confirm payment",
    "make payment",
    "delete account",
    "close account",
    "erase all data",
];

/// Apps where every tap is critical: Android permission prompts and app installers.
const CRITICAL_PACKAGES: &[&str] = &[
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
];

/// Phone and in-call apps, where a tap or swipe can place, answer, or end a call.
const CALL_PACKAGES: &[&str] = &[
    "com.android.dialer",
    "com.google.android.dialer",
    "com.samsung.android.dialer",
    "com.samsung.android.incallui",
    "com.android.incallui",
    "com.android.server.telecom",
    "com.android.phone",
    "com.oplus.dialer",
    "com.coloros.phonemanager",
];

/// Field names where pressing Enter looks something up instead of sending.
const SEARCH_FIELD_WORDS: &[&str] = &[
    "search",
    "find",
    "query",
    "url",
    "address",
    "omnibox",
    "lookup",
    "buscar",
    "suche",
    "recherche",
];

const SECRET_FIELD_WORDS: &[&str] = &[
    "password",
    "passcode",
    "passwort",
    "contraseña",
    "pin",
    "otp",
    "cvv",
    "cvc",
    "2fa",
    "mfa",
    "totp",
    "seed",
    "mnemonic",
];

const SECRET_FIELD_PHRASES: &[&str] = &[
    "one time",
    "one-time",
    "verification code",
    "security code",
    "card number",
    "recovery phrase",
    "private key",
    "secret key",
    "auth code",
];

fn words(text: &str) -> Vec<String> {
    text.to_lowercase()
        .split(|c: char| !c.is_alphanumeric())
        .filter(|w| !w.is_empty())
        .map(str::to_owned)
        .collect()
}

fn matches(node: &UiNode, single: &[&str], phrases: &[&str]) -> bool {
    [
        node.text.as_deref(),
        node.description.as_deref(),
        node.resource_id.as_deref(),
    ]
    .into_iter()
    .flatten()
    .any(|field| {
        let ws = words(field);
        let joined = format!(" {} ", ws.join(" "));
        let compact = ws.concat();
        ws.iter().any(|w| single.contains(&w.as_str()))
            || phrases.iter().any(|p| {
                let normalized = format!(" {} ", words(p).join(" "));
                joined.contains(&normalized)
            })
            // resource ids like `btnSignOut` or `sign_out` collapse to one token
            || ["signout", "logout"].iter().any(|t| single.contains(t) && compact.contains(t))
    })
}

/// True when the element's own label suggests an effect that needs a human.
pub fn is_consequential(node: &UiNode) -> bool {
    matches(node, CONSEQUENTIAL_WORDS, CONSEQUENTIAL_PHRASES)
}

/// Second line of defence behind the device's own sensitive-field detection.
pub fn looks_like_secret_field(node: &UiNode) -> bool {
    matches(node, SECRET_FIELD_WORDS, SECRET_FIELD_PHRASES)
}

#[cfg(test)]
mod tests;
