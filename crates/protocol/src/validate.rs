//! Bounds and shape checks applied at every boundary.
//!
//! The gateway validates commands before sending them and observations when
//! they arrive; devices validate commands again on receipt. Limits are part of
//! the protocol: a peer may reject anything beyond them.

use crate::{Command, CommandEnvelope, ErrorCode, Hello, Observation, ProtocolError, Target};

/// Largest text frame either side accepts. Screenshots dominate this budget.
pub const MAX_FRAME_BYTES: usize = 8 * 1024 * 1024;
/// Largest frame a gateway sends to a device (since 1.6 room for one file chunk).
pub const MAX_GATEWAY_FRAME_BYTES: usize = 1024 * 1024;
pub const MAX_TEXT_CHARS: usize = 2_000;
pub const MAX_NODES: u32 = 2_000;
pub const MAX_SWIPE_MS: u32 = 5_000;
pub const MAX_COORDINATE: i32 = 20_000;
pub const MAX_ID_CHARS: usize = 64;
pub const MAX_PACKAGE_CHARS: usize = 255;
pub const MAX_NODE_TEXT_CHARS: usize = 4_000;
pub const MAX_SCREENSHOT_BASE64_BYTES: usize = 6 * 1024 * 1024;
pub const MAX_SETTLE_MS: u32 = 3_000;
pub const MAX_QUIET_MS: u32 = 1_000;
pub const MAX_WAIT_MS: u32 = 15_000;
pub const MIN_WAIT_MS: u32 = 100;
pub const MAX_FIND_TEXT_CHARS: usize = 200;
/// Longest `owner.ask` message, in characters (since 1.5).
pub const MAX_ASK_OWNER_CHARS: usize = 300;
pub const MAX_SCROLL_SWIPES: u32 = 20;
pub const MAX_HOLD_MS: u32 = 3_000;
pub const MIN_PINCH_SPAN: u32 = 20;
/// Since 1.6: most raw bytes in one `file.read` or `file.write` chunk.
pub const MAX_FILE_CHUNK_BYTES: usize = 512 * 1024;
/// Since 1.6: most text a `file.preview` returns.
pub const MAX_PREVIEW_TEXT_BYTES: usize = 64 * 1024;
pub const MAX_FILE_NAME_CHARS: usize = 120;
pub const MAX_FILE_LIST: u32 = 200;
pub const MAX_SHARE_FILES: usize = 10;
pub const MAX_MIME_CHARS: usize = 100;
/// Since 1.7: largest file a link transfer may carry (gateways may set lower limits).
pub const MAX_LINK_FILE_BYTES: u64 = 4 * 1024 * 1024 * 1024;
/// Since 1.7: longest `FileLink::url` (signed storage URLs are long).
pub const MAX_LINK_URL_CHARS: usize = 4_096;
pub const MAX_LINK_HEADERS: usize = 8;
/// Since 1.7: most characters `clipboard.set` takes.
pub const MAX_CLIPBOARD_CHARS: usize = 10_000;
/// Since 1.8: most entries one `activity.list` returns.
pub const MAX_ACTIVITY_ENTRIES: u32 = 500;
/// Since 1.8: longest `task.done` summary.
pub const MAX_TASK_SUMMARY_CHARS: usize = 500;

/// A file or folder name: no path separators, control characters, or
/// leading dot (hidden files), and not "." or "..".
pub fn is_valid_file_name(name: &str) -> bool {
    let n = name.chars().count();
    n > 0
        && n <= MAX_FILE_NAME_CHARS
        && name.trim() == name
        && !name.starts_with('.')
        && !name.chars().any(|c| {
            c.is_control() || matches!(c, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|')
        })
}

/// `type/subtype`, lowercase-insensitive, no parameters.
pub fn is_valid_mime(mime: &str) -> bool {
    let mut parts = mime.split('/');
    let ok = |s: &str| {
        !s.is_empty()
            && s.bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'+' | b'-' | b'_'))
    };
    mime.len() <= MAX_MIME_CHARS
        && parts.next().is_some_and(ok)
        && parts.next().is_some_and(ok)
        && parts.next().is_none()
}

fn check_file_name(name: &str, what: &str) -> Result<(), ProtocolError> {
    if is_valid_file_name(name) {
        Ok(())
    } else {
        Err(invalid(format!(
            "{what} must be 1-{MAX_FILE_NAME_CHARS} characters, without / \\ : * ? \" < > | or a leading dot"
        )))
    }
}

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
        if after.quiet_ms.is_some_and(|q| q > MAX_QUIET_MS) {
            return Err(invalid(format!(
                "observe_after.quiet_ms must be at most {MAX_QUIET_MS}"
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

/// Text to look for on screen: 1-200 characters, no control characters.
fn check_find_text(text: &str) -> Result<(), ProtocolError> {
    if text.trim().is_empty() || text.chars().count() > MAX_FIND_TEXT_CHARS {
        return Err(invalid(format!(
            "text must be 1-{MAX_FIND_TEXT_CHARS} characters"
        )));
    }
    if text.chars().any(char::is_control) {
        return Err(invalid("text must not contain control characters"));
    }
    Ok(())
}

pub fn command(command: &Command) -> Result<(), ProtocolError> {
    match command {
        Command::Pinch {
            observation_id,
            center,
            start_span,
            end_span,
            duration_ms,
        } => {
            check_id("observation_id", observation_id)?;
            check_coordinate("center.x", center.x)?;
            check_coordinate("center.y", center.y)?;
            for (name, span) in [("start_span", start_span), ("end_span", end_span)] {
                if *span < MIN_PINCH_SPAN || *span > MAX_COORDINATE as u32 {
                    return Err(invalid(format!(
                        "{name} must be between {MIN_PINCH_SPAN} and {MAX_COORDINATE}"
                    )));
                }
            }
            if start_span == end_span {
                return Err(invalid("start_span and end_span must differ"));
            }
            if *duration_ms < 50 || *duration_ms > MAX_SWIPE_MS {
                return Err(invalid(format!(
                    "duration_ms must be between 50 and {MAX_SWIPE_MS}"
                )));
            }
            Ok(())
        }
        Command::WaitFor {
            text,
            timeout_ms,
            max_nodes,
            ..
        } => {
            check_find_text(text)?;
            if !(MIN_WAIT_MS..=MAX_WAIT_MS).contains(timeout_ms) {
                return Err(invalid(format!(
                    "timeout_ms must be between {MIN_WAIT_MS} and {MAX_WAIT_MS}"
                )));
            }
            if *max_nodes == 0 || *max_nodes > MAX_NODES {
                return Err(invalid(format!(
                    "max_nodes must be between 1 and {MAX_NODES}"
                )));
            }
            Ok(())
        }
        Command::ScrollTo {
            observation_id,
            text,
            container,
            max_swipes,
            ..
        } => {
            check_id("observation_id", observation_id)?;
            check_find_text(text)?;
            if let Some(container) = container {
                check_id("container", container)?;
            }
            if *max_swipes == 0 || *max_swipes > MAX_SCROLL_SWIPES {
                return Err(invalid(format!(
                    "max_swipes must be between 1 and {MAX_SCROLL_SWIPES}"
                )));
            }
            Ok(())
        }
        Command::AskOwner { message } => {
            let n = message.chars().count();
            if n == 0 || n > MAX_ASK_OWNER_CHARS || message.trim().is_empty() {
                return Err(invalid(format!(
                    "message must be 1-{MAX_ASK_OWNER_CHARS} characters"
                )));
            }
            if message.chars().any(|c| c.is_control() && c != '\n') {
                return Err(invalid("message must not contain control characters"));
            }
            Ok(())
        }
        Command::ListFiles {
            folder,
            query,
            limit,
            ..
        } => {
            if let Some(folder) = folder {
                check_id("folder", folder)?;
            }
            if let Some(query) = query {
                check_find_text(query)?;
            }
            if *limit == 0 || *limit > MAX_FILE_LIST {
                return Err(invalid(format!(
                    "limit must be between 1 and {MAX_FILE_LIST}"
                )));
            }
            Ok(())
        }
        Command::PreviewFile { id } | Command::DeleteFile { id } => check_id("id", id),
        Command::ReadFile { id, length, .. } => {
            check_id("id", id)?;
            if *length == 0 || *length as usize > MAX_FILE_CHUNK_BYTES {
                return Err(invalid(format!(
                    "length must be between 1 and {MAX_FILE_CHUNK_BYTES}"
                )));
            }
            Ok(())
        }
        Command::WriteFile {
            location,
            folder,
            subfolder,
            name,
            mime,
            data_base64,
            append,
            overwrite,
        } => {
            check_destination(*location, folder, subfolder, name, mime)?;
            if *append && *overwrite {
                return Err(invalid("a write either appends or overwrites, not both"));
            }
            if data_base64.len() > MAX_FILE_CHUNK_BYTES.div_ceil(3) * 4
                || !data_base64
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'+' | b'/' | b'='))
            {
                return Err(invalid(format!(
                    "data_base64 must be base64 of at most {MAX_FILE_CHUNK_BYTES} bytes"
                )));
            }
            Ok(())
        }
        Command::MakeFolder { folder, name } => {
            if let Some(folder) = folder {
                check_id("folder", folder)?;
            }
            check_file_name(name, "name")
        }
        Command::RenameFile { id, name } => {
            check_id("id", id)?;
            check_file_name(name, "name")
        }
        Command::Share { package, ids, text } => {
            if !is_valid_package(package) {
                return Err(invalid(
                    "package must be an application id such as com.example.app",
                ));
            }
            if ids.is_empty() || ids.len() > MAX_SHARE_FILES {
                return Err(invalid(format!(
                    "ids must hold 1-{MAX_SHARE_FILES} file ids"
                )));
            }
            for id in ids {
                check_id("ids", id)?;
            }
            if let Some(text) = text
                && (text.chars().count() > MAX_TEXT_CHARS
                    || text.chars().any(|c| c.is_control() && c != '\n'))
            {
                return Err(invalid(format!(
                    "text must be at most {MAX_TEXT_CHARS} characters without control characters"
                )));
            }
            Ok(())
        }
        Command::FetchFile {
            location,
            folder,
            subfolder,
            name,
            mime,
            link,
            sha256,
            size,
            ..
        } => {
            check_destination(*location, folder, subfolder, name, mime)?;
            check_link(link)?;
            if !is_hex(sha256, 64) {
                return Err(invalid("sha256 must be 64 lowercase hex characters"));
            }
            if size.is_some_and(|s| s > MAX_LINK_FILE_BYTES) {
                return Err(invalid(format!(
                    "size must be at most {MAX_LINK_FILE_BYTES} bytes"
                )));
            }
            Ok(())
        }
        Command::PushFile {
            id,
            link,
            max_bytes,
        } => {
            check_id("id", id)?;
            check_link(link)?;
            if *max_bytes == 0 || *max_bytes > MAX_LINK_FILE_BYTES {
                return Err(invalid(format!(
                    "max_bytes must be between 1 and {MAX_LINK_FILE_BYTES}"
                )));
            }
            Ok(())
        }
        Command::TransferStatus {
            transfer, wait_ms, ..
        } => {
            check_id("transfer", transfer)?;
            if *wait_ms > MAX_WAIT_MS {
                return Err(invalid(format!("wait_ms must be at most {MAX_WAIT_MS}")));
            }
            Ok(())
        }
        Command::SetClipboard { text } => {
            if text.is_empty() || text.chars().count() > MAX_CLIPBOARD_CHARS {
                return Err(invalid(format!(
                    "text must be 1-{MAX_CLIPBOARD_CHARS} characters"
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
        Command::ListActivity { limit, kinds, .. } => {
            if *limit == 0 || *limit > MAX_ACTIVITY_ENTRIES {
                return Err(invalid(format!(
                    "limit must be between 1 and {MAX_ACTIVITY_ENTRIES}"
                )));
            }
            if kinds.len() > 10 {
                return Err(invalid("kinds lists at most 10 kinds"));
            }
            Ok(())
        }
        Command::TaskDone { summary } => {
            if let Some(summary) = summary {
                if summary.chars().count() > MAX_TASK_SUMMARY_CHARS {
                    return Err(invalid(format!(
                        "summary must be at most {MAX_TASK_SUMMARY_CHARS} characters"
                    )));
                }
                if summary
                    .chars()
                    .any(|c| c.is_control() && c != '\n' && c != '\t')
                {
                    return Err(invalid("summary must not contain control characters"));
                }
            }
            Ok(())
        }
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
            long_press,
            double,
        } => {
            check_id("observation_id", observation_id)?;
            if *long_press && *double {
                return Err(invalid("a tap is either long_press or double, not both"));
            }
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
            hold_ms,
        } => {
            check_id("observation_id", observation_id)?;
            if *hold_ms > MAX_HOLD_MS {
                return Err(invalid(format!("hold_ms must be at most {MAX_HOLD_MS}")));
            }
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
            ..
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

/// Where `file.write` and `file.fetch` save: `folder` only in the picked
/// folder, `subfolder` only in photos and Downloads.
fn check_destination(
    location: crate::FileLocation,
    folder: &Option<String>,
    subfolder: &Option<String>,
    name: &str,
    mime: &Option<String>,
) -> Result<(), ProtocolError> {
    check_file_name(name, "name")?;
    if let Some(folder) = folder {
        if location != crate::FileLocation::Folder {
            return Err(invalid("folder is only for location \"folder\""));
        }
        check_id("folder", folder)?;
    }
    if let Some(subfolder) = subfolder {
        if location == crate::FileLocation::Folder {
            return Err(invalid("subfolder is for photos and downloads; use folder"));
        }
        check_file_name(subfolder, "subfolder")?;
    }
    if let Some(mime) = mime
        && !is_valid_mime(mime)
    {
        return Err(invalid("mime must look like type/subtype"));
    }
    Ok(())
}

/// `len` lowercase hex characters.
pub fn is_hex(value: &str, len: usize) -> bool {
    value.len() == len
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// A header a [`crate::FileLink`] may carry: storage hints, never credentials
/// or connection headers.
pub fn is_valid_link_header(name: &str, value: &str) -> bool {
    let name_ok = name == "content-type"
        || (name.len() > 2
            && name.len() <= 64
            && name.starts_with("x-")
            && name
                .bytes()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-'));
    name_ok && value.len() <= 512 && value.bytes().all(|b| (0x20..0x7f).contains(&b))
}

fn check_link(link: &crate::FileLink) -> Result<(), ProtocolError> {
    let url = link.url.as_str();
    let rest = url
        .strip_prefix("https://")
        .or_else(|| url.strip_prefix("http://"));
    if url.len() > MAX_LINK_URL_CHARS
        || rest.is_none_or(|r| r.is_empty() || r.starts_with('/'))
        || url.bytes().any(|b| !(0x21..0x7f).contains(&b))
    {
        return Err(invalid(format!(
            "link.url must be an http(s) URL of at most {MAX_LINK_URL_CHARS} characters"
        )));
    }
    if link.headers.len() > MAX_LINK_HEADERS
        || !link.headers.iter().all(|(k, v)| is_valid_link_header(k, v))
    {
        return Err(invalid(format!(
            "link.headers must be at most {MAX_LINK_HEADERS} x- or content-type headers with printable values"
        )));
    }
    if !is_hex(&link.key_hex, 64) || !is_hex(&link.iv_hex, 32) {
        return Err(invalid(
            "link.key_hex must be 64 and link.iv_hex 32 lowercase hex characters",
        ));
    }
    Ok(())
}

/// Checks an [`crate::ApprovalRequest`] from a phone (since 1.4).
pub fn approval_request(request: &crate::ApprovalRequest) -> Result<(), ProtocolError> {
    check_id("command_id", &request.command_id)?;
    if request.nonce.len() != 32
        || !request
            .nonce
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err(invalid("nonce must be 32 lowercase hex characters"));
    }
    check_text("title", &request.title, 200)?;
    check_text("detail", &request.detail, 600)?;
    if request.choices.is_empty() || request.choices.len() > 4 {
        return Err(invalid("choices must list 1-4 answers"));
    }
    for (i, c) in request.choices.iter().enumerate() {
        if request.choices[..i].contains(c) {
            return Err(invalid("choices must not repeat"));
        }
    }
    Ok(())
}

/// Non-empty, at most `max` characters, no control characters.
fn check_text(name: &str, value: &str, max: usize) -> Result<(), ProtocolError> {
    if value.trim().is_empty() || value.chars().count() > max || value.chars().any(char::is_control)
    {
        return Err(invalid(format!(
            "{name} must be 1-{max} characters without control characters"
        )));
    }
    Ok(())
}

/// Checks an approval answer for the phone (since 1.4).
pub fn approval_nonce(nonce: &str) -> Result<(), ProtocolError> {
    if nonce.len() == 32
        && nonce
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        Ok(())
    } else {
        Err(invalid("nonce must be 32 lowercase hex characters"))
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
            double: false,
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
            hold_ms: 0,
        };
        assert!(command(&swipe).is_err());

        let long_text = Command::TypeText {
            observation_id: "o_1".into(),
            element: "n1".into(),
            text: "x".repeat(MAX_TEXT_CHARS + 1),
            submit: false,
        };
        assert!(command(&long_text).is_err());

        let control = Command::TypeText {
            observation_id: "o_1".into(),
            element: "n1".into(),
            text: "abc\u{0007}".into(),
            submit: false,
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
