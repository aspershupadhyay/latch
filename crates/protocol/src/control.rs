// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Phone controls (since 1.11): volume, media keys, brightness, the Wi-Fi
//! and Bluetooth panels, and the flashlight. Each group is its own
//! capability, off until the owner switches it on.

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::Capability;

/// Which volume `PhoneControl::Volume` changes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum VolumeStream {
    /// Music, video, and games.
    Media,
    /// Incoming calls.
    Ring,
    Notification,
    Alarm,
}

/// A relative volume change: one step up or down, or mute and unmute.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum VolumeStep {
    Up,
    Down,
    Mute,
    Unmute,
}

/// A media key, sent to whichever app is playing.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum MediaAction {
    PlayPause,
    Play,
    Pause,
    Next,
    Previous,
    Stop,
}

/// A system settings panel the phone opens on screen. Android does not let
/// apps switch Wi-Fi or Bluetooth themselves; the panel shows the switch,
/// which the AI can then tap like any other.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum SettingsPanel {
    Wifi,
    Bluetooth,
}

/// One phone control. Serialized as `{"kind": "...", ...}`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum PhoneControl {
    /// Set `stream` to `percent` (0-100) or move it by `step`; exactly one of the two.
    Volume {
        stream: VolumeStream,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        percent: Option<u8>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        step: Option<VolumeStep>,
    },
    Media {
        action: MediaAction,
    },
    /// Set the screen brightness to `percent` (0-100), or switch automatic
    /// brightness on or off with `auto`; exactly one of the two.
    Brightness {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        percent: Option<u8>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        auto: Option<bool>,
    },
    Panel {
        panel: SettingsPanel,
    },
    Flashlight {
        on: bool,
    },
}

impl PhoneControl {
    /// The one capability this control needs.
    pub fn capability(&self) -> Capability {
        match self {
            PhoneControl::Volume { .. } => Capability::ControlVolume,
            PhoneControl::Media { .. } => Capability::ControlMedia,
            PhoneControl::Brightness { .. } => Capability::ControlBrightness,
            PhoneControl::Panel { .. } => Capability::ControlPanels,
            PhoneControl::Flashlight { .. } => Capability::ControlFlashlight,
        }
    }

    /// Only a panel changes what is on screen.
    pub fn changes_screen(&self) -> bool {
        matches!(self, PhoneControl::Panel { .. })
    }

    /// Plain words for approvals, the activity log, and tool answers, e.g.
    /// "Set the media volume to 40%". Gateways and the phone use the same text.
    pub fn describe(&self) -> String {
        match self {
            PhoneControl::Volume {
                stream,
                percent,
                step,
            } => {
                let name = match stream {
                    VolumeStream::Media => "media",
                    VolumeStream::Ring => "ring",
                    VolumeStream::Notification => "notification",
                    VolumeStream::Alarm => "alarm",
                };
                match (percent, step) {
                    (Some(p), _) => format!("Set the {name} volume to {p}%"),
                    (None, Some(VolumeStep::Up)) => format!("Turn the {name} volume up"),
                    (None, Some(VolumeStep::Down)) => format!("Turn the {name} volume down"),
                    (None, Some(VolumeStep::Mute)) => format!("Mute the {name} volume"),
                    (None, Some(VolumeStep::Unmute)) => format!("Unmute the {name} volume"),
                    (None, None) => format!("Change the {name} volume"),
                }
            }
            PhoneControl::Media { action } => match action {
                MediaAction::PlayPause => "Play or pause media".into(),
                MediaAction::Play => "Play media".into(),
                MediaAction::Pause => "Pause media".into(),
                MediaAction::Next => "Skip to the next track".into(),
                MediaAction::Previous => "Go to the previous track".into(),
                MediaAction::Stop => "Stop media".into(),
            },
            PhoneControl::Brightness { percent, auto } => match (percent, auto) {
                (Some(p), _) => format!("Set the screen brightness to {p}%"),
                (None, Some(true)) => "Turn automatic brightness on".into(),
                (None, Some(false)) => "Turn automatic brightness off".into(),
                (None, None) => "Change the screen brightness".into(),
            },
            PhoneControl::Panel { panel } => match panel {
                SettingsPanel::Wifi => "Open the Wi-Fi panel".into(),
                SettingsPanel::Bluetooth => "Open the Bluetooth panel".into(),
            },
            PhoneControl::Flashlight { on: true } => "Turn the flashlight on".into(),
            PhoneControl::Flashlight { on: false } => "Turn the flashlight off".into(),
        }
    }
}

/// What a control reads after the change, in `ActionResult::control` (since 1.11).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize, JsonSchema)]
pub struct ControlReading {
    /// Volume or brightness after the change, 0-100.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub percent: Option<u8>,
    /// Flashlight on, volume muted, or automatic brightness on.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub on: Option<bool>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wire_shape_and_words() {
        let c: PhoneControl =
            serde_json::from_str(r#"{"kind":"volume","stream":"media","percent":40}"#)
                .expect("json");
        assert_eq!(c.describe(), "Set the media volume to 40%");
        assert_eq!(c.capability(), Capability::ControlVolume);
        let c: PhoneControl =
            serde_json::from_str(r#"{"kind":"panel","panel":"wifi"}"#).expect("json");
        assert!(c.changes_screen());
        assert_eq!(
            serde_json::to_string(&PhoneControl::Flashlight { on: true }).expect("json"),
            r#"{"kind":"flashlight","on":true}"#
        );
    }
}
