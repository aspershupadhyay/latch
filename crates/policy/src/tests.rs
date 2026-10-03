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
        remote_approvals: false,
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
        double: false,
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
        double: false,
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
        submit: false,
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
        submit: false,
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
        hold_ms: 0,
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
        submit: false,
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
        submit: false,
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
        double: false,
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

/// The word lists are part of the policy contract: the TypeScript gateway reads
/// them from `packages/schemas/v1/policy/words.json`, which must match exactly.
/// Regenerate with `LATCH_UPDATE_SCHEMAS=1 cargo test -p latch-policy`.
#[test]
fn word_lists_match_shared_file() {
    let list = |words: &[&str]| {
        words
            .iter()
            .map(|w| format!("    \"{w}\""))
            .collect::<Vec<_>>()
            .join(",\n")
    };
    let generated = format!(
        "{{\n  \"consequential_words\": [\n{}\n  ],\n  \"consequential_phrases\": [\n{}\n  ],\n  \"critical_words\": [\n{}\n  ],\n  \"critical_phrases\": [\n{}\n  ],\n  \"critical_packages\": [\n{}\n  ],\n  \"call_packages\": [\n{}\n  ],\n  \"search_field_words\": [\n{}\n  ],\n  \"secret_field_words\": [\n{}\n  ],\n  \"secret_field_phrases\": [\n{}\n  ],\n  \"sensitive_app_words\": [\n{}\n  ]\n}}\n",
        list(CONSEQUENTIAL_WORDS),
        list(CONSEQUENTIAL_PHRASES),
        list(CRITICAL_WORDS),
        list(CRITICAL_PHRASES),
        list(CRITICAL_PACKAGES),
        list(CALL_PACKAGES),
        list(SEARCH_FIELD_WORDS),
        list(SECRET_FIELD_WORDS),
        list(SECRET_FIELD_PHRASES),
        list(SENSITIVE_APP_WORDS),
    );
    let path = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../packages/schemas/v1/policy/words.json"
    );
    if std::env::var_os("LATCH_UPDATE_SCHEMAS").is_some() {
        std::fs::write(path, &generated).expect("write words.json");
        return;
    }
    assert_eq!(
        std::fs::read_to_string(path).unwrap_or_default(),
        generated,
        "words.json is stale"
    );
}

fn child(id: &str, parent: &str, text: Option<&str>, description: Option<&str>) -> UiNode {
    let mut n = node(id, "", rect(900, 2200, 1080, 2400));
    n.parent = Some(parent.into());
    n.text = text.map(Into::into);
    n.description = description.map(Into::into);
    n.clickable = false;
    n
}

fn unlabeled(id: &str) -> UiNode {
    let mut n = node(id, "", rect(900, 2200, 1080, 2400));
    n.text = None;
    n.role = "FrameLayout".into();
    n
}

fn obs_of(package: &str, nodes: Vec<UiNode>) -> Observation {
    Observation {
        package: Some(package.into()),
        nodes,
        ..observation()
    }
}

fn judge(obs: &Observation, target: Target) -> Decision {
    let caps = all_enabled();
    evaluate(
        &Command::Tap {
            observation_id: "o_1".into(),
            target,
            long_press: false,
            double: false,
        },
        &DeviceContext {
            capabilities: &caps,
            session: session(),
            latest_observation: Some((obs, NOW)),
            now_ms: NOW,
        },
    )
}

fn confirm(decision: Decision) -> ConfirmRequest {
    match decision {
        Decision::Confirm(c) => c,
        other => panic!("expected confirm, got {other:?}"),
    }
}

/// Regression: a WhatsApp-style send button is an unlabeled clickable frame
/// whose label sits on the icon inside it. The tap must still ask.
#[test]
fn unlabeled_button_is_judged_by_the_icon_inside_it() {
    let obs = obs_of(
        "com.whatsapp",
        vec![unlabeled("n1"), child("n2", "n1", None, Some("Send"))],
    );
    for target in [
        Target::Element {
            element: "n1".into(),
        },
        Target::Point { x: 950, y: 2300 },
    ] {
        let c = confirm(judge(&obs, target));
        assert_eq!(c.risk, RiskLevel::High);
        assert_eq!(c.title, "Tap “Send” in com.whatsapp");
        assert_eq!(c.remember.as_deref(), Some("tap|com.whatsapp|send"));
    }
}

/// Regression: tapping a phone number, or anything in a phone app, can start a call.
#[test]
fn calls_need_approval() {
    let number = obs_of(
        "com.example.contacts",
        vec![node("n1", "+91 98765 43210", rect(0, 0, 500, 100))],
    );
    let c = confirm(judge(
        &number,
        Target::Element {
            element: "n1".into(),
        },
    ));
    assert!(c.remember.is_some());

    let sim = obs_of(
        "com.android.server.telecom",
        vec![node("n1", "Jio 4G", rect(0, 0, 500, 100))],
    );
    let c = confirm(judge(
        &sim,
        Target::Element {
            element: "n1".into(),
        },
    ));
    assert_eq!(c.title, "Tap “Jio 4G” in com.android.server.telecom");

    let chooser = obs_of(
        "com.example.chooser",
        vec![node("n1", "SIM 2", rect(0, 0, 500, 100))],
    );
    confirm(judge(
        &chooser,
        Target::Element {
            element: "n1".into(),
        },
    ));

    // A short number like a year or an amount is not a phone number.
    let plain = obs_of(
        "com.example",
        vec![node("n1", "2026", rect(0, 0, 500, 100))],
    );
    assert!(matches!(
        judge(
            &plain,
            Target::Element {
                element: "n1".into()
            }
        ),
        Decision::Allow { .. }
    ));
}

#[test]
fn critical_actions_are_never_rememberable() {
    for (package, label) in [
        ("com.example.shop", "Pay now"),
        ("com.example.shop", "Place order"),
        ("com.example.bank", "Transfer"),
        (
            "com.google.android.permissioncontroller",
            "While using the app",
        ),
        ("com.android.settings", "Delete account"),
    ] {
        let obs = obs_of(package, vec![node("n1", label, rect(0, 0, 500, 100))]);
        let c = confirm(judge(
            &obs,
            Target::Element {
                element: "n1".into(),
            },
        ));
        assert_eq!(c.risk, RiskLevel::High, "{label}");
        assert_eq!(c.remember, None, "{label} must be asked every time");
    }
}

#[test]
fn labels_from_outside_the_tapped_element_do_not_count() {
    // A parent row that says "Delete" does not make its unrelated child consequential.
    let mut row = node("n1", "Delete", rect(0, 0, 1080, 200));
    row.clickable = false;
    let mut open = node("n2", "Open", rect(0, 0, 500, 200));
    open.parent = Some("n1".into());
    let obs = obs_of("com.example", vec![row, open]);
    assert!(matches!(
        judge(
            &obs,
            Target::Element {
                element: "n2".into()
            }
        ),
        Decision::Allow { .. }
    ));
}

#[test]
fn unlabeled_element_borrows_the_nearest_labeled_ancestor() {
    let mut holder = node("n1", "", rect(900, 2200, 1080, 2400));
    holder.text = None;
    holder.description = Some("Voice call".into());
    holder.clickable = false;
    let mut button = unlabeled("n2");
    button.parent = Some("n1".into());
    let obs = obs_of("com.example.chat", vec![holder, button]);
    let c = confirm(judge(
        &obs,
        Target::Element {
            element: "n2".into(),
        },
    ));
    assert_eq!(c.title, "Tap “Voice call” in com.example.chat");
}

#[test]
fn sensitive_apps_are_recognised_by_package() {
    for pkg in [
        "com.phonepe.app",
        "net.one97.paytm",
        "com.google.android.apps.nbu.paisa.user",
        "in.org.npci.upiapp",
        "com.csam.icici.bank.imobile",
        "com.x8bit.bitwarden.vault",
    ] {
        assert!(is_sensitive_app(pkg), "{pkg}");
    }
    for pkg in [
        "com.android.settings",
        "com.whatsapp",
        "com.google.android.apps.maps",
    ] {
        assert!(!is_sensitive_app(pkg), "{pkg}");
    }
}
