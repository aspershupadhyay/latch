//! A tiny, deterministic phone UI: a launcher, a settings app, a chat app with
//! a consequential Send button, and a login screen with a password field.

use latch_protocol::{AppEntry, Rect, UiNode};

pub const WIDTH: u32 = 1080;
pub const HEIGHT: u32 = 2400;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Screen {
    Launcher,
    Settings,
    Network,
    Chat,
    Login,
}

/// Everything the fake apps remember.
#[derive(Debug, Clone)]
pub struct Phone {
    pub screen: Screen,
    pub back_stack: Vec<Screen>,
    pub wifi_on: bool,
    pub draft: String,
    pub sent_messages: Vec<String>,
    pub username: String,
    pub settings_scrolled: bool,
}

impl Default for Phone {
    fn default() -> Self {
        Self {
            screen: Screen::Launcher,
            back_stack: Vec::new(),
            wifi_on: true,
            draft: String::new(),
            sent_messages: Vec::new(),
            username: String::new(),
            settings_scrolled: false,
        }
    }
}

/// What tapping an element does.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Effect {
    None,
    Open(Screen),
    ToggleWifi,
    SendMessage,
    EditDraft,
    EditUsername,
    EditPassword,
}

#[derive(Debug, Clone)]
pub struct Node {
    pub node: UiNode,
    pub effect: Effect,
}

fn rect(left: i32, top: i32, right: i32, bottom: i32) -> Rect {
    Rect {
        left,
        top,
        right,
        bottom,
    }
}

fn base(id: usize, parent: Option<usize>, role: &str, bounds: Rect) -> UiNode {
    UiNode {
        id: format!("n{id}"),
        parent: parent.map(|p| format!("n{p}")),
        role: role.into(),
        text: None,
        description: None,
        resource_id: None,
        bounds,
        clickable: false,
        long_clickable: false,
        editable: false,
        scrollable: false,
        checked: None,
        enabled: true,
        focused: false,
        sensitive: false,
    }
}

pub const APPS: &[(&str, &str, Screen)] = &[
    ("com.android.settings", "Settings", Screen::Settings),
    ("org.latch.demo.chat", "Chat", Screen::Chat),
    ("org.latch.demo.login", "Bank login", Screen::Login),
];

pub fn apps() -> Vec<AppEntry> {
    APPS.iter()
        .map(|(package, label, _)| AppEntry {
            package: (*package).into(),
            label: (*label).into(),
        })
        .collect()
}

pub fn package_of(screen: &Screen) -> &'static str {
    match screen {
        Screen::Launcher => "com.android.launcher",
        Screen::Settings | Screen::Network => "com.android.settings",
        Screen::Chat => "org.latch.demo.chat",
        Screen::Login => "org.latch.demo.login",
    }
}

impl Phone {
    pub fn render(&self) -> Vec<Node> {
        let mut nodes = vec![Node {
            node: base(
                0,
                None,
                "FrameLayout",
                rect(0, 0, WIDTH as i32, HEIGHT as i32),
            ),
            effect: Effect::None,
        }];
        let mut push = |mut node: UiNode, effect: Effect, text: Option<&str>| {
            node.text = text.map(Into::into);
            nodes.push(Node { node, effect });
        };
        match self.screen {
            Screen::Launcher => {
                for (i, (_, label, screen)) in APPS.iter().enumerate() {
                    let top = 300 + i as i32 * 260;
                    let mut n = base(i + 1, Some(0), "TextView", rect(80, top, 1000, top + 220));
                    n.clickable = true;
                    n.description = Some(format!("Open {label}"));
                    push(n, Effect::Open(screen.clone()), Some(label));
                }
            }
            Screen::Settings => {
                let mut list = base(1, Some(0), "RecyclerView", rect(0, 200, 1080, 2400));
                list.scrollable = true;
                push(list, Effect::None, None);
                let mut items = vec![
                    ("Network & internet", Effect::Open(Screen::Network)),
                    ("Connected devices", Effect::None),
                    ("Display", Effect::None),
                    ("Battery", Effect::None),
                ];
                if self.settings_scrolled {
                    items.push(("About phone", Effect::None));
                }
                for (i, (label, effect)) in items.into_iter().enumerate() {
                    let top = 240 + i as i32 * 180;
                    let mut n = base(i + 2, Some(1), "TextView", rect(40, top, 1040, top + 160));
                    n.clickable = true;
                    push(n, effect, Some(label));
                }
            }
            Screen::Network => {
                let mut wifi = base(1, Some(0), "Switch", rect(40, 300, 1040, 460));
                wifi.clickable = true;
                wifi.checked = Some(self.wifi_on);
                push(wifi, Effect::ToggleWifi, Some("Wi-Fi"));
            }
            Screen::Chat => {
                for (i, message) in self.sent_messages.iter().enumerate() {
                    let top = 200 + i as i32 * 120;
                    push(
                        base(i + 10, Some(0), "TextView", rect(40, top, 1040, top + 100)),
                        Effect::None,
                        Some(message),
                    );
                }
                let mut input = base(1, Some(0), "EditText", rect(40, 2200, 880, 2360));
                input.editable = true;
                input.clickable = true;
                input.description = Some("Message".into());
                let draft = (!self.draft.is_empty()).then_some(self.draft.as_str());
                push(input, Effect::EditDraft, draft);
                let mut send = base(2, Some(0), "Button", rect(900, 2200, 1060, 2360));
                send.clickable = true;
                send.resource_id = Some("org.latch.demo.chat:id/send".into());
                push(send, Effect::SendMessage, Some("Send"));
            }
            Screen::Login => {
                let mut user = base(1, Some(0), "EditText", rect(40, 600, 1040, 760));
                user.editable = true;
                user.description = Some("Username".into());
                let username = (!self.username.is_empty()).then_some(self.username.as_str());
                push(user, Effect::EditUsername, username);
                let mut password = base(2, Some(0), "EditText", rect(40, 800, 1040, 960));
                password.editable = true;
                password.sensitive = true;
                push(password, Effect::EditPassword, None);
                let mut sign_in = base(3, Some(0), "Button", rect(40, 1000, 1040, 1160));
                sign_in.clickable = true;
                push(sign_in, Effect::None, Some("Sign in"));
            }
        }
        nodes
    }

    pub fn open(&mut self, screen: Screen) {
        let previous = std::mem::replace(&mut self.screen, screen);
        self.back_stack.push(previous);
    }

    pub fn back(&mut self) {
        self.screen = self.back_stack.pop().unwrap_or(Screen::Launcher);
    }

    pub fn home(&mut self) {
        self.back_stack.clear();
        self.screen = Screen::Launcher;
    }
}
