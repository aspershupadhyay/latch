//! The Latch device protocol, version 1.
//!
//! This crate is the normative definition of what travels between a Latch
//! gateway and a phone. It is deliberately independent of MCP: MCP is one
//! adapter on top of the gateway, while this protocol is the boundary every
//! device implementation (Android, iOS, the fake device) must honour.
//!
//! Wire format: UTF-8 JSON text frames over an authenticated WebSocket. Every
//! frame is one [`DeviceToGateway`] or [`GatewayToDevice`] message. The JSON
//! Schema in `packages/schemas/v1` and the fixtures beside it are generated
//! from, and tested against, these types.

mod capability;
mod command;
mod error;
mod message;
mod observation;
pub mod validate;

pub use capability::{Capability, CapabilityState, CapabilityStatus};
pub use command::{
    Command, ConfirmRequest, Direction, GlobalAction, MAX_REMEMBER_CHARS, Point, RiskLevel, Target,
};
pub use error::{ErrorCode, ProtocolError};
pub use message::{
    ActionResult, AppEntry, AppList, CommandEnvelope, DeviceDescriptor, DeviceInfo,
    DeviceToGateway, GatewayToDevice, Hello, ObserveAfter, Outcome, SessionInfo, WaitResult,
};
pub use observation::{Observation, Rect, ScreenInfo, Screenshot, UiNode};

/// Protocol version spoken by this crate, as `major.minor`.
///
/// Peers are compatible when the major versions match. Minor versions only
/// add optional fields, which every implementation must tolerate.
pub const PROTOCOL_VERSION: &str = "1.3";

/// Minor version of a compatible `major.minor` string, e.g. 3 for "1.3".
pub fn minor_version(version: &str) -> Option<u32> {
    if !is_compatible(version) {
        return None;
    }
    version.split('.').nth(1)?.parse().ok()
}

/// Returns true when a peer advertising `version` can talk to this crate.
pub fn is_compatible(version: &str) -> bool {
    let ours = PROTOCOL_VERSION.split('.').next();
    let mut parts = version.split('.');
    let major = parts.next();
    let minor_ok = parts.next().is_some_and(|m| m.parse::<u32>().is_ok());
    minor_ok && parts.next().is_none() && major.is_some() && major == ours
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_compatibility_requires_matching_major() {
        assert!(is_compatible("1.0"));
        assert!(is_compatible("1.7"));
        assert!(!is_compatible("2.0"));
        assert!(!is_compatible("1"));
        assert!(!is_compatible("1.x"));
        assert!(!is_compatible("1.0.0"));
        assert!(!is_compatible(""));
    }
}
