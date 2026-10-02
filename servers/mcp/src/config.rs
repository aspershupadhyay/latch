//! Gateway configuration, read from environment variables.
//!
//! Every variable is documented in `docs/operations/gateway.md`.

use std::net::SocketAddr;
use std::path::PathBuf;

#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    #[error(
        "{0} is not set. Generate one with `latch-gateway gen-token` and set it in the environment."
    )]
    MissingToken(&'static str),
    #[error("{0} must be at least 32 characters. Generate one with `latch-gateway gen-token`.")]
    WeakToken(&'static str),
    #[error("LATCH_ADMIN_TOKEN and LATCH_MCP_TOKEN must be different")]
    SharedToken,
    #[error("{name} is not a valid socket address: {value}")]
    BadAddress { name: &'static str, value: String },
}

#[derive(Clone)]
pub struct Config {
    pub bind: SocketAddr,
    pub data_dir: PathBuf,
    /// Grants pairing, device listing, and revocation. Never give it to an AI client.
    pub admin_token: String,
    /// Optional static MCP token from the environment. Owners can also create
    /// per-client tokens in the console; both are accepted.
    pub mcp_token: Option<String>,
    /// False in stdio mode, where MCP arrives over the parent's pipe instead of `/mcp`.
    pub mcp_http: bool,
    /// Public base URL shown to phones during pairing, e.g. `https://latch.example.com`.
    pub public_url: Option<String>,
    /// Browser origins allowed to call `/mcp`. Requests without an Origin header are
    /// unaffected; requests with any other Origin are rejected (DNS-rebinding defence).
    pub allowed_origins: Vec<String>,
}

impl std::fmt::Debug for Config {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Config")
            .field("bind", &self.bind)
            .field("data_dir", &self.data_dir)
            .field("public_url", &self.public_url)
            .field("allowed_origins", &self.allowed_origins)
            .finish_non_exhaustive()
    }
}

fn token(name: &'static str) -> Result<String, ConfigError> {
    let value = std::env::var(name).map_err(|_| ConfigError::MissingToken(name))?;
    let value = value.trim().to_owned();
    if value.len() < 32 {
        return Err(ConfigError::WeakToken(name));
    }
    Ok(value)
}

impl Config {
    pub fn from_env(stdio: bool) -> Result<Self, ConfigError> {
        let bind = match (std::env::var("LATCH_BIND"), std::env::var("PORT")) {
            (Ok(value), _) => value.parse().map_err(|_| ConfigError::BadAddress {
                name: "LATCH_BIND",
                value,
            })?,
            // Hosting platforms (Cloud Run, Render, Railway, Fly) inject PORT and expect 0.0.0.0.
            (Err(_), Ok(port)) => {
                format!("0.0.0.0:{port}")
                    .parse()
                    .map_err(|_| ConfigError::BadAddress {
                        name: "PORT",
                        value: port,
                    })?
            }
            (Err(_), Err(_)) => SocketAddr::from(([127, 0, 0, 1], 8787)),
        };
        let admin_token = token("LATCH_ADMIN_TOKEN")?;
        let mcp_token = match std::env::var("LATCH_MCP_TOKEN") {
            Ok(v) if !v.trim().is_empty() => Some(token("LATCH_MCP_TOKEN")?),
            _ => None,
        };
        if mcp_token.as_deref() == Some(admin_token.as_str()) {
            return Err(ConfigError::SharedToken);
        }
        Ok(Self {
            bind,
            data_dir: std::env::var_os("LATCH_DATA_DIR")
                .map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from("latch-data")),
            admin_token,
            mcp_token,
            mcp_http: !stdio,
            public_url: std::env::var("LATCH_PUBLIC_URL")
                .ok()
                .map(|u| u.trim_end_matches('/').to_owned())
                .filter(|u| !u.is_empty()),
            allowed_origins: std::env::var("LATCH_ALLOWED_ORIGINS")
                .map(|v| {
                    v.split(',')
                        .map(|o| o.trim().trim_end_matches('/').to_owned())
                        .filter(|o| !o.is_empty())
                        .collect()
                })
                .unwrap_or_default(),
        })
    }
}
