# Latch

**Let any MCP-capable AI see and operate an Android phone — only in the ways its owner allows.**

Latch is an open-source runtime with three parts:

- **Gateway** — the MCP server your AI connects to and the endpoint your phone connects to. Every person runs **their own**: one click on Vercel (free tier, with Upstash Redis), or a ~11 MB container on any server. Latch runs no relay and never sees your keys or screens.
- **Android app** (Kotlin, Jetpack Compose) — sets up or joins your gateway, and during a session you start reads the screen and taps, swipes, and types through an accessibility service, within the switches you set.
- **Device protocol 1.1** — a versioned, MCP-independent contract with JSON Schemas, shared fixtures, and shared policy and rendering contracts that both gateway implementations are tested against.

```text
Claude / ChatGPT / Cursor / any MCP app ──MCP──▶ your gateway (Vercel or container) ◀──HTTPS long-poll── Latch app on your phone
```

[![Deploy with Vercel](https://vercel.com/button)](https://vercel.com/new/clone?repository-url=https%3A%2F%2Fgithub.com%2Faspershupadhyay%2Flatch&root-directory=servers%2Fvercel&project-name=latch-gateway&repository-name=latch-gateway&env=LATCH_ADMIN_TOKEN&envDescription=At%20least%2032%20random%20characters%20(the%20Latch%20app%20generates%20one%20for%20you)&stores=%5B%7B%22type%22%3A%22integration%22%2C%22integrationSlug%22%3A%22upstash%22%2C%22productSlug%22%3A%22upstash-kv%22%2C%22protocol%22%3A%22storage%22%7D%5D)

> **Status: alpha, not yet verified on a physical phone.** Both gateways (Rust and Vercel) pass end-to-end tests with the official MCP TypeScript SDK client and a simulated phone, including against Redis via the Upstash REST protocol. The Android app's real transport and setup code are tested on the JVM against both gateways; its UI is snapshot-tested. Running it on real phones is the next gate ([checklist](docs/platform/android.md#real-device-gate-s4)). The Vercel deployment itself has not been exercised from this repository's CI, because that needs a Vercel account.

### iPhone

iOS does not let any third-party app read other apps' screens or tap for you — there is no public API for it, and Latch will not use private ones. An honest iPhone companion is planned with what Apple allows: screen viewing while you broadcast it (ReplayKit), opening links and Shortcuts, and app-owned actions. It cannot operate other apps the way Android can. See [handbook chapter 06](handbook/06-ios-application-plan.md).

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

**On your phone (recommended):** install the Android app → **Create my own gateway** → copy the owner key → **Open Vercel** (sign in, keep the Upstash store, paste the key, Deploy) → paste your new address back → **Connect**. Then **Connect** tab → create a key for your AI app and paste it there. Full guide: [docs/operations/vercel.md](docs/operations/vercel.md).

**Connect any MCP app** with a key from the app or the web console:

| The app asks for | Give it |
|---|---|
| Just a URL (ChatGPT, Claude.ai connectors) | the secret link `https://<you>.vercel.app/mcp/<key>` |
| URL + headers (Claude Code, Cursor, VS Code, SDKs) | `https://<you>.vercel.app/mcp` + `Authorization: Bearer <key>` |

**Self-host instead:** `deploy/docker-compose.yml` (VPS + automatic HTTPS) or any container platform — [docs/operations/gateway.md](docs/operations/gateway.md).

**Try it without a phone** (needs Rust): `scripts/dev.sh` starts a gateway and a simulated phone and prints a ready-to-paste Claude Code command.

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
| `servers/mcp` | `latch-gateway` (Rust): MCP, phone channel, pairing, owner console |
| `servers/vercel` | The same gateway for Vercel Functions + Upstash Redis |
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
