use super::*;
use latch_protocol::{Capability, GlobalAction, Point, Rect, ScreenInfo};

const NOW: u64 = 1_800_000_000_000;

fn node(id: &str, text: &str, bounds: Rect) -> UiNode {
    UiNode {
        id: id.into(),
        parent: None,
        role: "Button".into(),
        text: Some(text.into()),
        description: None,
        resource_id: None,
        bounds,
        clickable: true,
        long_clickable: false,
        editable: false,
        scrollable: false,
        checked: None,
        enabled: true,
        focused: false,
        sensitive: false,
    }
}

fn rect(left: i32, top: i32, right: i32, bottom: i32) -> Rect {
    Rect {
        left,
        top,
        right,
        bottom,
    }
}

fn observation() -> Observation {
    let mut password = node("n3", "", rect(0, 300, 1080, 400));
    password.text = None;
    password.editable = true;
    password.sensitive = true;
    let mut search = node("n4", "Search", rect(0, 400, 1080, 500));
    search.editable = true;
    let mut pin = node("n5", "Enter PIN", rect(0, 500, 1080, 600));
    pin.editable = true;
    let mut disabled = node("n6", "Next", rect(0, 600, 1080, 700));
    disabled.enabled = false;
    Observation {
        observation_id: "o_1".into(),
        captured_at_ms: NOW,
        package: Some("com.example.chat".into()),
        screen: ScreenInfo {
            width: 1080,
            height: 2400,
            rotation: 0,
        },
        nodes: vec![
            node("n1", "Settings", rect(0, 100, 1080, 200)),
            node("n2", "Send", rect(900, 2200, 1080, 2400)),
            password,
            search,
            pin,
            disabled,
        ],
        screenshot: None,
        redacted_count: 1,
        truncated: false,
    }
}

fn all_enabled() -> Vec<CapabilityState> {
    Capability::ALL
        .iter()
        .map(|&capability| CapabilityState {
            capability,
            status: CapabilityStatus::Enabled,
        })
        .collect()
}

fn session() -> SessionInfo {
    SessionInfo {
        expires_at_ms: NOW + 60_000,
        approve_every_action: false,
        paused: false,
    }
}

fn decide(
    command: &Command,
    caps: &[CapabilityState],
    session: SessionInfo,
    obs: Option<(&Observation, u64)>,
) -> Decision {
    evaluate(
        command,
        &DeviceContext {
            capabilities: caps,
            session,
            latest_observation: obs,
            now_ms: NOW,
        },
    )
}

fn tap(element: &str) -> Command {
    Command::Tap {
        observation_id: "o_1".into(),
        target: Target::Element {
            element: element.into(),
        },
        long_press: false,
    }
}

fn code(decision: Decision) -> Option<ErrorCode> {
    match decision {
        Decision::Deny(e) => Some(e.code),
        _ => None,
    }
}

#[test]
fn harmless_tap_is_allowed() {
    let obs = observation();
    let d = decide(&tap("n1"), &all_enabled(), session(), Some((&obs, NOW)));
    assert_eq!(
        d,
        Decision::Allow {
            risk: RiskLevel::Medium
        }
    );
}

#[test]
fn consequential_tap_requires_confirmation_naming_target_and_app() {
    let obs = observation();
    match decide(&tap("n2"), &all_enabled(), session(), Some((&obs, NOW))) {
        Decision::Confirm(c) => {
            assert_eq!(c.risk, RiskLevel::High);
            assert_eq!(c.title, "Tap “Send” in com.example.chat");
        }
        other => panic!("expected confirmation, got {other:?}"),
    }
}

#[test]
fn coordinate_tap_on_consequential_control_also_requires_confirmation() {
    let obs = observation();
    let cmd = Command::Tap {
        observation_id: "o_1".into(),
        target: Target::Point { x: 1000, y: 2300 },
        long_press: false,
    };
    assert!(matches!(
        decide(&cmd, &all_enabled(), session(), Some((&obs, NOW))),
        Decision::Confirm(_)
    ));
}

#[test]
fn approve_every_action_mode_confirms_harmless_actions_but_not_reads() {
    let obs = observation();
    let mut s = session();
    s.approve_every_action = true;
    assert!(matches!(
        decide(&tap("n1"), &all_enabled(), s, Some((&obs, NOW))),
        Decision::Confirm(_)
    ));
    let read = Command::Observe {
        include_screenshot: true,
        max_nodes: 100,
    };
    assert!(matches!(
        decide(&read, &all_enabled(), s, None),
        Decision::Allow { .. }
    ));
}

#[test]
fn disabled_capability_is_refused_with_permission_missing() {
    let obs = observation();
    let caps: Vec<_> = all_enabled()
        .into_iter()
        .map(|mut c| {
            if c.capability == Capability::InputGesture {
                c.status = CapabilityStatus::Disabled;
            }
            c
        })
        .collect();
    assert_eq!(
        code(decide(&tap("n1"), &caps, session(), Some((&obs, NOW)))),
        Some(ErrorCode::PermissionMissing)
    );
}

#[test]
fn screenshot_requires_its_own_capability() {
    let caps = vec![CapabilityState {
        capability: Capability::UiObserve,
        status: CapabilityStatus::Enabled,
    }];
    let with_shot = Command::Observe {
        include_screenshot: true,
        max_nodes: 100,
    };
    let tree_only = Command::Observe {
        include_screenshot: false,
        max_nodes: 100,
    };
    assert_eq!(
        code(decide(&with_shot, &caps, session(), None)),
        Some(ErrorCode::UnsupportedCapability)
    );
    assert!(matches!(
        decide(&tree_only, &caps, session(), None),
        Decision::Allow { .. }
    ));
}

#[test]
fn paused_and_expired_sessions_refuse_everything() {
    let mut paused = session();
    paused.paused = true;
    let info = Command::DeviceInfo {};
    assert_eq!(
        code(decide(&info, &all_enabled(), paused, None)),
        Some(ErrorCode::DeviceUnavailable)
    );
    let mut expired = session();
    expired.expires_at_ms = NOW;
    assert_eq!(
        code(decide(&info, &all_enabled(), expired, None)),
        Some(ErrorCode::DeviceUnavailable)
    );
}

#[test]
fn actions_need_the_latest_fresh_observation() {
    let obs = observation();
    assert_eq!(
        code(decide(&tap("n1"), &all_enabled(), session(), None)),
        Some(ErrorCode::StaleObservation)
    );
    let mut other = observation();
    other.observation_id = "o_2".into();
    assert_eq!(
        code(decide(
            &tap("n1"),
            &all_enabled(),
            session(),
            Some((&other, NOW))
        )),
        Some(ErrorCode::StaleObservation)
    );
    let old = NOW - MAX_OBSERVATION_AGE_MS - 1;
    assert_eq!(
        code(decide(
            &tap("n1"),
            &all_enabled(),
            session(),
            Some((&obs, old))
        )),
        Some(ErrorCode::StaleObservation)
    );
}

#[test]
fn sensitive_fields_are_never_targeted() {
    let obs = observation();
    let ctx = (&obs, NOW);
    assert_eq!(
        code(decide(&tap("n3"), &all_enabled(), session(), Some(ctx))),
        Some(ErrorCode::SensitiveTarget)
    );
    let type_password = Command::TypeText {
        observation_id: "o_1".into(),
        element: "n3".into(),
        text: "hunter2".into(),
    };
    assert_eq!(
        code(decide(&type_password, &all_enabled(), session(), Some(ctx))),
        Some(ErrorCode::SensitiveTarget)
    );
    // Not flagged by the device, but labelled like a secret field.
    let type_pin = Command::TypeText {
        observation_id: "o_1".into(),
        element: "n5".into(),
        text: "1234".into(),
    };
    assert_eq!(
        code(decide(&type_pin, &all_enabled(), session(), Some(ctx))),
        Some(ErrorCode::SensitiveTarget)
    );
    let swipe_from_password = Command::Swipe {
        observation_id: "o_1".into(),
        from: Point { x: 10, y: 350 },
        to: Point { x: 10, y: 900 },
        duration_ms: 300,
    };
    assert_eq!(
        code(decide(
            &swipe_from_password,
            &all_enabled(),
            session(),
            Some(ctx)
        )),
        Some(ErrorCode::SensitiveTarget)
    );
}

#[test]
fn typing_requires_an_editable_target() {
    let obs = observation();
    let ok = Command::TypeText {
        observation_id: "o_1".into(),
        element: "n4".into(),
        text: "weather".into(),
    };
    assert_eq!(
        decide(&ok, &all_enabled(), session(), Some((&obs, NOW))),
        Decision::Allow {
            risk: RiskLevel::Medium
        }
    );
    let not_editable = Command::TypeText {
        observation_id: "o_1".into(),
        element: "n1".into(),
        text: "x".into(),
    };
    assert_eq!(
        code(decide(
            &not_editable,
            &all_enabled(),
            session(),
            Some((&obs, NOW))
        )),
        Some(ErrorCode::InvalidRequest)
    );
}

#[test]
fn missing_disabled_and_offscreen_targets_are_refused() {
    let obs = observation();
    let ctx = Some((&obs, NOW));
    assert_eq!(
        code(decide(&tap("n99"), &all_enabled(), session(), ctx)),
        Some(ErrorCode::TargetNotFound)
    );
    assert_eq!(
        code(decide(&tap("n6"), &all_enabled(), session(), ctx)),
        Some(ErrorCode::TargetNotFound)
    );
    let off = Command::Tap {
        observation_id: "o_1".into(),
        target: Target::Point { x: 5000, y: 10 },
        long_press: false,
    };
    assert_eq!(
        code(decide(&off, &all_enabled(), session(), ctx)),
        Some(ErrorCode::InvalidRequest)
    );
}

#[test]
fn invalid_requests_never_reach_capability_checks() {
    let launch = Command::LaunchApp {
        package: "intent://x".into(),
    };
    assert_eq!(
        code(decide(&launch, &[], session(), None)),
        Some(ErrorCode::InvalidRequest)
    );
}

#[test]
fn navigation_is_low_risk() {
    let back = Command::Global {
        action: GlobalAction::Back,
    };
    assert_eq!(
        decide(&back, &all_enabled(), session(), None),
        Decision::Allow {
            risk: RiskLevel::Low
        }
    );
}

#[test]
fn consequential_detection() {
    let mk = |text: &str| node("n", text, rect(0, 0, 1, 1));
    for label in [
        "Send",
        "Pay now",
        "DELETE",
        "Place order",
        "Sign out",
        "Confirm purchase",
        "Enviar",
    ] {
        assert!(
            is_consequential(&mk(label)),
            "{label} should be consequential"
        );
    }
    for label in [
        "Settings",
        "Wi-Fi",
        "Search",
        "Back",
        "Sender details",
        "Payment history",
    ] {
        // Whole-word matching: "Sender" and "Payment" are not "send" and "pay".
        assert!(
            !is_consequential(&mk(label)),
            "{label} should not be consequential"
        );
    }
    let mut by_id = mk("");
    by_id.text = None;
    by_id.resource_id = Some("com.example:id/btnSignOut".into());
    assert!(is_consequential(&by_id));
    by_id.resource_id = Some("com.example:id/sign_out".into());
    assert!(is_consequential(&by_id));
}
