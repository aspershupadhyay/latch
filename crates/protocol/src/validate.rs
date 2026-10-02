//! Bounds and shape checks applied at every boundary.
//!
//! The gateway validates commands before sending them and observations when
//! they arrive; devices validate commands again on receipt. Limits are part of
//! the protocol: a peer may reject anything beyond them.

use crate::{Command, CommandEnvelope, ErrorCode, Hello, Observation, ProtocolError, Target};

/// Largest text frame either side accepts. Screenshots dominate this budget.
pub const MAX_FRAME_BYTES: usize = 8 * 1024 * 1024;
/// Largest frame a gateway sends to a device.
pub const MAX_GATEWAY_FRAME_BYTES: usize = 64 * 1024;
pub const MAX_TEXT_CHARS: usize = 2_000;
pub const MAX_NODES: u32 = 2_000;
pub const MAX_SWIPE_MS: u32 = 5_000;
pub const MAX_COORDINATE: i32 = 20_000;
pub const MAX_ID_CHARS: usize = 64;
pub const MAX_PACKAGE_CHARS: usize = 255;
pub const MAX_NODE_TEXT_CHARS: usize = 4_000;
pub const MAX_SCREENSHOT_BASE64_BYTES: usize = 6 * 1024 * 1024;
pub const MAX_SETTLE_MS: u32 = 3_000;

fn invalid(message: impl Into<String>) -> ProtocolError {
    ProtocolError::new(ErrorCode::InvalidRequest, message)
}

/// Opaque identifiers: 1..=64 chars of `[A-Za-z0-9_-]`.
pub fn is_valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= MAX_ID_CHARS
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

/// Android-style application id: dot-separated segments of `[A-Za-z0-9_]`,
/// each starting with a letter, at least two segments.
pub fn is_valid_package(package: &str) -> bool {
    if package.is_empty() || package.len() > MAX_PACKAGE_CHARS {
        return false;
    }
    let segments: Vec<&str> = package.split('.').collect();
    segments.len() >= 2
        && segments.iter().all(|s| {
            let mut bytes = s.bytes();
            bytes.next().is_some_and(|b| b.is_ascii_alphabetic())
                && bytes.all(|b| b.is_ascii_alphanumeric() || b == b'_')
        })
}

fn check_coordinate(name: &str, value: i32) -> Result<(), ProtocolError> {
    if (0..=MAX_COORDINATE).contains(&value) {
        Ok(())
    } else {
        Err(invalid(format!(
            "{name} must be between 0 and {MAX_COORDINATE}"
        )))
    }
}

fn check_id(name: &str, id: &str) -> Result<(), ProtocolError> {
    if is_valid_id(id) {
        Ok(())
    } else {
        Err(invalid(format!(
            "{name} must be 1-{MAX_ID_CHARS} characters of letters, digits, '_' or '-'"
        )))
    }
}

/// A whole command as a device receives it: the command and, since 1.2, `observe_after`.
pub fn envelope(envelope: &CommandEnvelope) -> Result<(), ProtocolError> {
    command(&envelope.command)?;
    if let Some(key) = envelope
        .confirm
        .as_ref()
        .and_then(|c| c.remember.as_deref())
        && (key.is_empty()
            || key.chars().count() > crate::MAX_REMEMBER_CHARS
            || key.chars().any(char::is_control))
    {
        return Err(invalid(format!(
            "confirm.remember must be 1-{} characters without control characters",
            crate::MAX_REMEMBER_CHARS
        )));
    }
    if let Some(after) = &envelope.observe_after {
        if !envelope.command.is_action() {
            return Err(invalid("observe_after is only allowed on actions"));
        }
        if after.settle_ms > MAX_SETTLE_MS {
            return Err(invalid(format!(
                "observe_after.settle_ms must be at most {MAX_SETTLE_MS}"
            )));
        }
        if after.max_nodes == 0 || after.max_nodes > MAX_NODES {
            return Err(invalid(format!(
                "observe_after.max_nodes must be between 1 and {MAX_NODES}"
            )));
        }
    }
    Ok(())
}

pub fn command(command: &Command) -> Result<(), ProtocolError> {
    match command {
        Command::DeviceInfo {} | Command::ListApps {} | Command::Global { .. } => Ok(()),
        Command::Observe { max_nodes, .. } => {
            if *max_nodes == 0 || *max_nodes > MAX_NODES {
                Err(invalid(format!(
                    "max_nodes must be between 1 and {MAX_NODES}"
                )))
            } else {
                Ok(())
            }
        }
        Command::Tap {
            observation_id,
            target,
            ..
        } => {
            check_id("observation_id", observation_id)?;
            match target {
                Target::Element { element } => check_id("element", element),
                Target::Point { x, y } => {
                    check_coordinate("x", *x)?;
                    check_coordinate("y", *y)
                }
            }
        }
        Command::Swipe {
            observation_id,
            from,
            to,
            duration_ms,
        } => {
            check_id("observation_id", observation_id)?;
            for (name, v) in [
                ("from.x", from.x),
                ("from.y", from.y),
                ("to.x", to.x),
                ("to.y", to.y),
            ] {
                check_coordinate(name, v)?;
            }
            if *duration_ms < 50 || *duration_ms > MAX_SWIPE_MS {
                return Err(invalid(format!(
                    "duration_ms must be between 50 and {MAX_SWIPE_MS}"
                )));
            }
            if from == to {
                return Err(invalid("swipe start and end must differ"));
            }
            Ok(())
        }
        Command::TypeText {
            observation_id,
            element,
            text,
        } => {
            check_id("observation_id", observation_id)?;
            check_id("element", element)?;
            if text.chars().count() > MAX_TEXT_CHARS {
                return Err(invalid(format!(
                    "text is limited to {MAX_TEXT_CHARS} characters"
                )));
            }
            if text
                .chars()
                .any(|c| c.is_control() && c != '\n' && c != '\t')
            {
                return Err(invalid("text must not contain control characters"));
            }
            Ok(())
        }
        Command::LaunchApp { package } => {
            if is_valid_package(package) {
                Ok(())
            } else {
                Err(invalid(
                    "package must be an application id such as com.example.app",
                ))
            }
        }
    }
}

pub fn hello(hello: &Hello) -> Result<(), ProtocolError> {
    if !crate::is_compatible(&hello.protocol) {
        return Err(ProtocolError::new(
            ErrorCode::UnsupportedCapability,
            format!(
                "device speaks protocol {}, gateway speaks {}",
                truncate(&hello.protocol, 16),
                crate::PROTOCOL_VERSION
            ),
        ));
    }
    let d = &hello.device;
    for (name, value) in [
        ("platform", &d.platform),
        ("os_version", &d.os_version),
        ("model", &d.model),
        ("app_version", &d.app_version),
    ] {
        if value.is_empty() || value.chars().count() > 64 {
            return Err(invalid(format!("device.{name} must be 1-64 characters")));
        }
    }
    if hello.capabilities.len() > 64 {
        return Err(invalid("too many capabilities"));
    }
    Ok(())
}

pub fn observation(obs: &Observation, max_nodes: u32) -> Result<(), ProtocolError> {
    check_id("observation_id", &obs.observation_id)?;
    if obs.nodes.len() > max_nodes.min(MAX_NODES) as usize {
        return Err(invalid("observation has more nodes than requested"));
    }
    if let Some(package) = &obs.package
        && package.len() > MAX_PACKAGE_CHARS
    {
        return Err(invalid("package is too long"));
    }
    for node in &obs.nodes {
        check_id("node id", &node.id)?;
        let text_len = node.text.as_deref().map_or(0, str::len)
            + node.description.as_deref().map_or(0, str::len);
        if text_len > MAX_NODE_TEXT_CHARS {
            return Err(invalid("node text exceeds the protocol limit"));
        }
        if node.sensitive && (node.text.is_some() || node.description.is_some()) {
            // A device that leaks sensitive content is broken; refuse to forward it.
            return Err(ProtocolError::new(
                ErrorCode::Internal,
                "device sent content for a sensitive element",
            ));
        }
    }
    if let Some(shot) = &obs.screenshot {
        if shot.mime != "image/jpeg" && shot.mime != "image/png" {
            return Err(invalid("screenshot must be image/jpeg or image/png"));
        }
        if shot.data_base64.len() > MAX_SCREENSHOT_BASE64_BYTES {
            return Err(invalid("screenshot exceeds the protocol size limit"));
        }
    }
    Ok(())
}

fn truncate(s: &str, max: usize) -> String {
    s.chars().take(max).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{GlobalAction, Point};

    #[test]
    fn ids_are_restricted() {
        assert!(is_valid_id("o_abc-12"));
        assert!(!is_valid_id(""));
        assert!(!is_valid_id("has space"));
        assert!(!is_valid_id(&"a".repeat(65)));
        assert!(!is_valid_id("ünïcode"));
    }

    #[test]
    fn packages_are_restricted() {
        assert!(is_valid_package("com.android.settings"));
        assert!(is_valid_package("org.mozilla.firefox_beta"));
        assert!(!is_valid_package("settings"));
        assert!(!is_valid_package("com..x"));
        assert!(!is_valid_package("com.1x"));
        assert!(!is_valid_package("intent://evil#Intent;end"));
        assert!(!is_valid_package("com.example/.Main"));
    }

    #[test]
    fn rejects_out_of_range_input() {
        let tap = Command::Tap {
            observation_id: "o_1".into(),
            target: Target::Point { x: -1, y: 5 },
            long_press: false,
        };
        assert_eq!(
            command(&tap).map_err(|e| e.code),
            Err(ErrorCode::InvalidRequest)
        );

        let swipe = Command::Swipe {
            observation_id: "o_1".into(),
            from: Point { x: 1, y: 1 },
            to: Point { x: 1, y: 1 },
            duration_ms: 300,
        };
        assert!(command(&swipe).is_err());

        let long_text = Command::TypeText {
            observation_id: "o_1".into(),
            element: "n1".into(),
            text: "x".repeat(MAX_TEXT_CHARS + 1),
        };
        assert!(command(&long_text).is_err());

        let control = Command::TypeText {
            observation_id: "o_1".into(),
            element: "n1".into(),
            text: "abc\u{0007}".into(),
        };
        assert!(command(&control).is_err());

        assert!(
            command(&Command::Observe {
                include_screenshot: false,
                max_nodes: 0
            })
            .is_err()
        );
        assert!(
            command(&Command::Global {
                action: GlobalAction::Back
            })
            .is_ok()
        );
    }
}
