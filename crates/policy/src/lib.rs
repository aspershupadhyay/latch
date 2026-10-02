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
}

const AGENT_DETAIL: &str = "Requested by an AI agent connected through Latch.";

fn assess(
    command: &Command,
    observation: Option<&Observation>,
) -> Result<Assessment, ProtocolError> {
    let low = |title: String| Assessment {
        risk: RiskLevel::Low,
        title,
        detail: AGENT_DETAIL.into(),
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
        Command::LaunchApp { package } => Ok(Assessment {
            risk: RiskLevel::Medium,
            title: format!("Open {package}"),
            detail: AGENT_DETAIL.into(),
        }),
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
            Ok(Assessment {
                risk: RiskLevel::Medium,
                title: "Swipe on the screen".into(),
                detail: AGENT_DETAIL.into(),
            })
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
            if let Some(node) = node {
                if node.sensitive {
                    return Err(sensitive());
                }
                let label = label_of(node);
                let place = obs
                    .package
                    .as_deref()
                    .map(|p| format!(" in {p}"))
                    .unwrap_or_default();
                if is_consequential(node) {
                    return Ok(Assessment {
                        risk: RiskLevel::High,
                        title: format!("{verb} “{label}”{place}"),
                        detail: format!(
                            "{AGENT_DETAIL} This control may send, buy, delete, publish, or change something that is hard to undo."
                        ),
                    });
                }
                return Ok(Assessment {
                    risk: RiskLevel::Medium,
                    title: format!("{verb} “{label}”{place}"),
                    detail: AGENT_DETAIL.into(),
                });
            }
            Ok(Assessment {
                risk: RiskLevel::Medium,
                title: format!("{verb} on the screen"),
                detail: AGENT_DETAIL.into(),
            })
        }
        Command::TypeText { element, text, .. } => {
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
            Ok(Assessment {
                risk: RiskLevel::Medium,
                title: format!(
                    "Type {} characters into “{}”",
                    text.chars().count(),
                    label_of(node)
                ),
                detail: AGENT_DETAIL.into(),
            })
        }
    }
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
