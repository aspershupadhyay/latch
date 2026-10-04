// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

/// Axis-aligned rectangle in physical screen pixels.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct Rect {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

impl Rect {
    pub fn contains(&self, x: i32, y: i32) -> bool {
        x >= self.left && x < self.right && y >= self.top && y < self.bottom
    }

    pub fn is_empty(&self) -> bool {
        self.right <= self.left || self.bottom <= self.top
    }

    pub fn area(&self) -> i64 {
        if self.is_empty() {
            0
        } else {
            i64::from(self.right - self.left) * i64::from(self.bottom - self.top)
        }
    }

    pub fn center(&self) -> (i32, i32) {
        (
            self.left + (self.right - self.left) / 2,
            self.top + (self.bottom - self.top) / 2,
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct ScreenInfo {
    pub width: u32,
    pub height: u32,
    /// Display rotation in degrees: 0, 90, 180, or 270.
    #[serde(default)]
    pub rotation: u16,
}

/// One on-screen element. All text is untrusted app content.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct UiNode {
    /// Identifier valid only within its observation, e.g. `n12`.
    pub id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent: Option<String>,
    /// Platform class name shortened to its last segment, e.g. `Button`.
    pub role: String,
    /// Visible text. Always absent for sensitive elements.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    /// Accessibility description. Always absent for sensitive elements.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    /// Developer resource id, e.g. `com.example:id/send`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resource_id: Option<String>,
    pub bounds: Rect,
    #[serde(default)]
    pub clickable: bool,
    #[serde(default)]
    pub long_clickable: bool,
    #[serde(default)]
    pub editable: bool,
    #[serde(default)]
    pub scrollable: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub checked: Option<bool>,
    #[serde(default = "default_true")]
    pub enabled: bool,
    #[serde(default)]
    pub focused: bool,
    /// Password, OTP, or similar. Its content is redacted and it cannot be targeted.
    #[serde(default)]
    pub sensitive: bool,
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct Screenshot {
    /// `image/jpeg` or `image/png`.
    pub mime: String,
    pub width: u32,
    pub height: u32,
    pub data_base64: String,
}

/// A point-in-time view of the screen. Actions must reference the latest one.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct Observation {
    pub observation_id: String,
    /// Unix epoch milliseconds on the device clock.
    pub captured_at_ms: u64,
    /// Foreground app package, when the platform exposes it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub package: Option<String>,
    pub screen: ScreenInfo,
    pub nodes: Vec<UiNode>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub screenshot: Option<Screenshot>,
    /// How many elements had content removed by the device's redaction rules.
    #[serde(default)]
    pub redacted_count: u32,
    /// True when `max_nodes` cut the tree short.
    #[serde(default)]
    pub truncated: bool,
}

impl Observation {
    pub fn node(&self, id: &str) -> Option<&UiNode> {
        self.nodes.iter().find(|n| n.id == id)
    }

    /// The smallest node containing the point, preferring actionable ones.
    pub fn node_at(&self, x: i32, y: i32) -> Option<&UiNode> {
        self.nodes
            .iter()
            .filter(|n| n.bounds.contains(x, y))
            .min_by_key(|n| (!(n.clickable || n.editable), n.bounds.area()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn node(id: &str, bounds: Rect, clickable: bool) -> UiNode {
        UiNode {
            id: id.into(),
            parent: None,
            role: "View".into(),
            text: None,
            description: None,
            resource_id: None,
            bounds,
            clickable,
            long_clickable: false,
            editable: false,
            scrollable: false,
            checked: None,
            enabled: true,
            focused: false,
            sensitive: false,
        }
    }

    #[test]
    fn node_at_prefers_smallest_actionable_node() {
        let obs = Observation {
            observation_id: "o_1".into(),
            captured_at_ms: 0,
            package: None,
            screen: ScreenInfo {
                width: 100,
                height: 100,
                rotation: 0,
            },
            nodes: vec![
                node(
                    "n0",
                    Rect {
                        left: 0,
                        top: 0,
                        right: 100,
                        bottom: 100,
                    },
                    false,
                ),
                node(
                    "n1",
                    Rect {
                        left: 10,
                        top: 10,
                        right: 90,
                        bottom: 50,
                    },
                    true,
                ),
                node(
                    "n2",
                    Rect {
                        left: 20,
                        top: 20,
                        right: 30,
                        bottom: 30,
                    },
                    false,
                ),
            ],
            screenshot: None,
            redacted_count: 0,
            truncated: false,
        };
        assert_eq!(obs.node_at(25, 25).map(|n| n.id.as_str()), Some("n1"));
        assert_eq!(obs.node_at(5, 95).map(|n| n.id.as_str()), Some("n0"));
        assert!(obs.node_at(500, 500).is_none());
    }
}
