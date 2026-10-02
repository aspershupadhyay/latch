# Latch

**Let any MCP-capable AI see and operate an Android phone — only in the ways its owner allows.**

Latch is an open-source runtime with three parts:

- **Gateway** (Rust, one small binary or container): an MCP server for AI clients and the endpoint phones connect to. Run it in any cloud container, on a VPS, or at home. There is no Latch-operated relay.
- **Android app** (Kotlin, Jetpack Compose): pairs with your gateway and, during a session you start, reads the screen and taps, swipes, and types through an accessibility service.
- **Device protocol v1**: a versioned, MCP-independent contract with JSON Schemas and shared fixtures.

```text
AI client ──MCP (HTTPS)──▶ latch-gateway ◀──WebSocket (phone dials out)── Android app
```

> **Status: early alpha, not yet verified on real phones.** The gateway, protocol, and safety policy are tested end to end against a simulated phone and the official MCP TypeScript SDK client. The Android app builds and passes unit tests and lint; real-device verification is the next gate ([checklist](docs/platform/android.md#real-device-gate-s4)). iOS is planned as a smaller, honest companion.

## Safety model

- Everything except basic device information starts **off**; the owner switches capabilities on one by one.
- Sessions are started by the owner, time out by themselves, and show a red **Stop** button on screen the whole time.
- Taps on controls that send, buy, delete, publish, or change accounts wait for the owner's **approval** on the phone. The owner can require approval for every action.
- **Password, PIN, one-time-code, and payment fields** are redacted and can never be tapped or typed into.
- Agents cannot see or operate Latch itself, the notification shade, or the lock screen.
- Every action must cite the **latest screen observation**; if the screen changed, it is refused.
- Screen text is passed to the model as untrusted data, never as instructions.
- No shell, file, notification, or credential access exists in the product.

Details: [handbook chapter 08](handbook/08-safety-privacy-security.md), [chapter 17](handbook/17-plan-review-and-revised-delivery.md), [SECURITY.md](SECURITY.md).

## Quick start

**Try it without a phone** (needs Rust):

```sh
scripts/dev.sh        # gateway on 127.0.0.1:8787 + a simulated phone; prints tokens
claude mcp add --transport http latch http://127.0.0.1:8787/mcp --header "Authorization: Bearer <LATCH_MCP_TOKEN>"
```

Then ask your AI client to "open Settings on the phone and turn off Wi-Fi".

**Run it for real:**

1. Deploy the gateway with HTTPS: `deploy/docker-compose.yml` (VPS + automatic certificates) or any container platform — see [docs/operations/gateway.md](docs/operations/gateway.md).
2. Open `https://your-gateway/`, enter the admin token, and create a pairing code.
3. Install the Android app ([build](docs/platform/android.md#build) or download a release), enter the gateway address and code, turn on the accessibility service, choose capabilities, start a session.
4. Connect your AI client to `https://your-gateway/mcp` with the MCP token.

## MCP tools

| Tool | Does |
|---|---|
| `list_devices` | Paired phones, connection state, enabled capabilities |
| `observe` | Element tree (ids, roles, text, bounds, what accepts taps/text/scroll) and optional screenshot |
| `tap`, `type_text`, `scroll`, `swipe`, `press` | Act on the latest observation; each returns the next observation |
| `list_apps`, `launch_app` | See and open installed apps |

## Repository

| Path | What |
|---|---|
| `crates/protocol` | Device protocol v1 types, limits, validation |
| `crates/policy` | Pure authorization decisions |
| `crates/fake-device` | Simulated phone for tests and demos |
| `servers/mcp` | `latch-gateway`: MCP, phone channel, pairing, owner console |
| `apps/android` | Android app |
| `packages/schemas/v1` | Generated JSON Schemas and shared fixtures |
| `tests/interop` | Official MCP TypeScript SDK client check |
| `handbook/` | Product and engineering plan (start with the README) |
| `docs/` | Operations, platform, protocol, decisions |
| `scripts/` | `check.sh`, `dev.sh`, `interop.sh`, cloud setup |

## Develop

```sh
scripts/cloud-session-setup.sh   # once per fresh container: Rust deps, Android SDK, test client
scripts/check.sh                 # everything CI runs: Rust, Android, interop
```

See [CONTRIBUTING.md](CONTRIBUTING.md). Licensed under [Apache-2.0](LICENSE).
