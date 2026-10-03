# Architecture decision records

ADR-001 to ADR-010 live in [handbook chapter 16](../../handbook/16-adr-index-decision-log.md). The records below were made when implementation started (2026-10-02) and are explained in [chapter 17](../../handbook/17-plan-review-and-revised-delivery.md). Supersede a record with a new one; do not rewrite history.

## ADR-011 — Deliver in vertical slices
**Status:** accepted · **Amends:** chapter 13 phase order
**Decision:** build one thin end-to-end loop (protocol → gateway → device → MCP client) first, then harden each layer, instead of finishing phases 1–13 in sequence.
**Why:** protocol and UX mistakes surface only end to end; the sequential plan delayed the first loop to phase 6.
**Reversal trigger:** slices repeatedly ship with unresolved safety gates.

## ADR-012 — The phone dials out; the gateway is cloud-hostable
**Status:** accepted · **Amends:** ADR-005, phase 8
**Decision:** phones open an authenticated WebSocket to the gateway (`/v1/device`). The same gateway binary runs in any cloud container, on a home server, or on a LAN. There is no Latch-operated relay.
**Why:** phones cannot accept inbound connections behind NAT/CGNAT; a laptop daemon is not acceptable.
**Consequence:** a cloud-hosted gateway sees screen content in transit. Self-hosting is the privacy answer.
**Reversal trigger:** a transport that gives end-to-end encryption between phone and MCP client without a trusted gateway.

## ADR-013 — Screenshots through AccessibilityService; minSdk 30
**Status:** accepted · **Amends:** chapter 05 MediaProjection path
**Decision:** use `AccessibilityService.takeScreenshot()` (Android 11+). Defer MediaProjection to a future video-stream capability.
**Why:** no per-session capture consent, no `mediaProjection` foreground service, honours `FLAG_SECURE`.
**Cost:** Android 10 and older are unsupported.
**Reversal trigger:** platform rate limits make single screenshots unusable for agents.

## ADR-014 — Bearer tokens over TLS before custom envelope crypto
**Status:** accepted · **Amends:** crate plan in chapter 04
**Decision:** 256-bit random tokens (stored as SHA-256 hashes, compared in constant time), single-use 10-minute pairing codes with rate limiting, TLS from the hosting platform. No `crypto` crate yet.
**Why:** the threat model is met by standard transport security; custom envelope signing adds risk before it adds value.
**Next step:** device-held keys (Android Keystore) signing results, and OAuth 2.1 for MCP clients.

## ADR-015 — Hand-written MCP layer instead of `rmcp`
**Status:** accepted · **Amends:** chapter 04 stack
**Decision:** implement JSON-RPC 2.0 for `initialize`, `ping`, `tools/list`, `tools/call` over Streamable HTTP (JSON responses, no server stream) and stdio, pinned to MCP versions 2025-11-25, 2025-06-18, 2025-03-26, 2024-11-05.
**Why:** a few hundred auditable lines versus a fast-moving 3.x SDK; the surface Latch needs is tiny.
**Guard:** CI drives the gateway with the official MCP TypeScript SDK client (`scripts/interop.sh`).
**Reversal trigger:** Latch needs resources, prompts, sampling, server-initiated streams, or OAuth flows that the SDK already implements well.

## ADR-016 — Approvals as an overlay; one command at a time
**Status:** accepted · **Amends:** chapter 07 human-in-the-loop, chapter 09 confirmation sheet
**Decision:** approval requests are drawn as an accessibility overlay card above the current app, with Approve enabled after one second. Each phone runs strictly one command at a time (gateway lock and device queue). Agents cannot observe or act in Latch itself, the notification shade, or the lock screen, and gestures may not start in the status bar or on the Stop pill.
**Why:** works without notification permission, keeps the owner in context, and makes it impossible for the agent to approve its own request.
**Reversal trigger:** an Android change that lets other apps draw over or interact with accessibility overlays.

## ADR-017 — Every owner runs their own gateway; HTTP long-poll is the phone transport
**Status:** accepted (2026-10-02) · **Amends:** ADR-012, chapter 03 transport strategy
**Decision:** each person deploys their own gateway — one click to their own Vercel account (with Upstash Redis), or a container they run. Phones talk to any gateway over the protocol 1.1 HTTP long-poll binding (`/v1/device/hello`, `/poll`, `/messages`); the WebSocket channel remains for compatibility.
**Why:** serverless hosts cannot hold sockets; one phone transport for every host keeps the app simple and testable; nobody pays for anyone else's hosting and no third party sees anyone's screens.
**Cost:** up to one poll interval (default 1 s) of extra latency on Vercel; Redis command usage while a session is active.
**Reversal trigger:** a free serverless host with durable sockets, or measured latency that makes agents unusable.

## ADR-018 — A TypeScript gateway for Vercel, held to the Rust one by shared contracts
**Status:** accepted · **Amends:** ADR-002 (Rust owns the gateway)
**Decision:** `servers/vercel` re-implements the gateway in TypeScript for Vercel Functions. Behaviour is pinned by shared files both implementations test against: policy cases and word lists, the MCP tool catalog and instructions, exact rendering of observations and errors, and the protocol fixtures. `scripts/sync-vercel.sh --check` runs in CI.
**Why:** Vercel's first-class runtime is Node; a Rust-on-Vercel or WASM build would be harder for contributors to run and debug.
**Reversal trigger:** the contracts stop catching drift (a behaviour difference reaches users), or a maintained Rust/WASM path on Vercel becomes simpler.

## ADR-019 — Per-app MCP keys, including a secret-link form
**Status:** accepted · **Amends:** single `LATCH_MCP_TOKEN`
**Decision:** owners create one MCP key per AI app (phone or console), revocable individually. Keys work as `Authorization: Bearer` or as a secret link `/mcp/<key>` for clients that accept only a URL. The admin key never works as an MCP key.
**Why:** "any MCP app" includes apps whose connector settings take only a URL; per-app keys limit the blast radius of a leak.
**Risk:** URLs end up in logs and histories more easily than headers. **Next:** OAuth 2.1 for clients that support it.

## ADR-020 — Bento layout for the app and console
**Status:** accepted · **Amends:** chapter 09 visual direction
**Decision:** screens are bento grids of rounded tiles; one gradient hero tile carries state (indigo active, amber approval, red stopped, slate idle); other colour is reserved for meaning. Signal Field, copy tone, and accessibility rules are unchanged.
**Why:** owner feedback that the first UI felt flat; a bento grid shows the session, capabilities, AI connection, and activity at a glance.

## ADR-021 — Owner-chosen apps instead of per-action approvals
**Status:** accepted (2026-10-03, owner request) · **Amends:** ADR-016, chapter 08 confirmation design, chapter 17 §9 P0 approval tiers
**Decision:** the owner switches apps on in Access → Apps (every launchable app, with a switch). The AI may use only apps that are switched on; the first time it needs an app that is off, the phone asks once ("Let the AI use WhatsApp?": Not now / This session / Always). In an app that is switched on, consequential actions (send, post, delete, call, Enter in a chat) run without asking and are listed in Activity as "Done without asking (app switched on)". Critical actions (payments and transfers, app installs, Android permission prompts, account deletion) still ask on the phone every time, and "Ask me before every action" still overrides everything. Apps whose package or name suggests money, accounts, or passwords (`sensitive_app_words` in `packages/schemas/v1/policy/words.json`) get an extra warning before the owner switches them on, and both gateways add a caution line to their observations telling the AI to get the user's go-ahead in the chat. The launcher is always usable; Latch and system UI stay refused. The AI's screen access follows the same rule: an app that is not switched on is never observed, and an action that lands in one returns no screen until the owner answers. The server instructions tell the AI to finish by listing what it did on the phone.
**Why:** the owner found per-action approvals unusable for real tasks ("asking each action makes me sick"). Approving an app once matches chapter 08's "actions on a new app or destination" rule and its "bounded repeat policy the user explicitly selects". Keeping the critical tier on the phone means a prompt-injected or compromised AI client still cannot move money, install apps, or grant permissions, which per-app trust alone would allow.
**Not built:** approving inside the AI app (MCP elicitation). Whoever holds the AI key, or an AI client tricked by screen text, could answer such a prompt; the phone is the one place the owner proves presence. With apps switched on, phone prompts are left for the rare critical actions. Revisit with a threat review if owners still find them too frequent.
**Risk:** inside a switched-on app, a manipulated AI can send or delete without a prompt. Mitigations: per-app scope chosen by the owner, the Activity log, the Stop pill, session expiry, the caution line for money apps, and "Ask me before every action" for owners who want the old behaviour.
**Reversal trigger:** evidence that agents send or delete things owners did not intend inside switched-on apps.

## ADR-022 — The owner may let switched-on apps run critical actions without asking
**Status:** accepted (2026-10-03, owner decision) · **Amends:** ADR-021, AGENTS.md "fresh confirmation" rule, chapter 08 confirmation design
**Decision:** Access → Apps has one switch, off by default, "Also allow payments and permissions". Turning it on takes a confirmation that names the risk. While it is on, in apps the owner switched on, critical actions (payments and transfers, purchases, app installs, account deletion) and Android's permission and install dialogs that appear over them run without asking; Activity lists each one as "Done without asking (you allow payments and permissions)". "Ask me before every action" still overrides it, "Switch all off" resets it, and Stop still ends everything. Latch still never types into password, PIN, one-time-code, or payment fields, so a payment that needs the owner's PIN still needs the owner.
**Why:** the owner decides what their phone does; the earlier fixed rule ("critical always asks") was the project's default, not a choice the owner could make.
**Risk:** a prompt-injected or compromised AI can buy, transfer, install, or grant permissions without a prompt in switched-on apps. Mitigations: off by default, explicit confirmation, per-app scope, the money-app caution line to the AI, the sensitive-field refusal, Activity, Stop, and session expiry.
**Reversal trigger:** an owner reports an unwanted payment or install made under this switch.

## ADR-023 — Answering Latch's questions in the AI app (protocol 1.4)
**Status:** accepted (2026-10-03, owner request) · **Amends:** ADR-016, ADR-021 "not built"
**Decision:** an owner switch, off by default ("Answer questions in the AI app too"), lets approvals and "Let the AI use …?" questions be answered in the AI app as well as on the phone. While the phone waits, it tells the gateway (`approval_request`, protocol 1.4); the Vercel gateway, for MCP clients that declared the `elicitation` capability and accept event streams, streams the tool call and sends `elicitation/create` with the same question and answers, then relays the user's choice (`approval_answer`). Decline means deny; dismissing leaves the phone's card open. The phone's card stays up; the first answer wins; the phone accepts a relayed answer only with a matching nonce, an offered choice, and the switch on. Every gateway now keeps waiting while the phone waits for the owner, so a "Let the AI use …?" question on an observe no longer times out after 20 s. The Rust gateway keeps waiting but does not relay answers yet.
**Fallback for AI apps without elicitation** (most chat apps today, including Claude's connectors): the tool call ends with "Latch is waiting for the owner", the question, and a `request_id`; the AI asks the user in the chat and passes on their answer with the `answer_approval` tool (marked destructive, so apps that confirm risky tools ask the person first), which relays it to the phone and returns what the paused action returned. The phone's card stays up meanwhile; whichever answer arrives first counts, and answers outside the offered choices are refused. The Rust gateway lists the tool but answers that only the phone can answer there.
**Why:** owners are often not holding the phone while their AI works from a computer.
**Risk:** whoever controls the AI app (its user, a compromised client, or an attacker holding the MCP key) can answer; elicitation is shown to the person, not the model, in compliant clients, but the gateway cannot prove that. Hence opt-in, per phone, visible on the switch.
**Reversal trigger:** evidence of answers given without the owner, or MCP clients that let the model answer elicitations.

## ADR-024 — Auto mode: every app, no questions, with the owner's consent
**Status:** accepted (2026-10-03, owner request) · **Amends:** ADR-021, ADR-022
**Decision:** Access has an "Auto mode" switch, off by default. Turning it on opens a confirmation that lists what it allows (sending, posting, deleting, paying, installing, answering Android's permission pop-ups) and offers "Yes, for this session" or "Yes, until I turn it off". While it is on, every app counts as switched on and critical actions run as under ADR-022; Activity lists each one as "Done without asking (Auto mode)". Session Auto mode ends when the session stops or expires and is never saved. The lasting choice is saved, and "Switch all off" clears it. "Ask me before every action" still overrides it. Latch and system UI stay refused, Latch still never types into password, PIN, one-time-code, or payment fields, and Stop still ends everything.
**Why:** the owner asked for one consented switch for unattended tasks instead of switching apps on one by one.
**Risk:** the widest scope Latch offers. A prompt-injected or compromised AI can act in any app, including money apps, without a prompt. Mitigations: off by default, explicit consent naming the risk, the per-session option, the money-app caution line to the AI, the sensitive-field refusal, Activity, Stop, and session expiry.
**Reversal trigger:** an owner reports an unwanted action taken under Auto mode that they could not have expected from the consent text.

## ADR-025 — Plain words and an Apple-style look
**Status:** accepted (2026-10-03, owner request) · **Amends:** ADR-020
**Decision:** the app uses a deep indigo accent on iOS-style grouped lists (grey canvas, white cards, solid icon squircles, iOS-style switches) in light and dark. The UI avoids technical terms: the gateway is "your relay", the MCP address is "your AI link", and tokens are "keys". Every text field has a one-line hint under its name and an ⓘ button that explains it. Settings and the welcome screen open a "How to set up Latch" guide. The approval card in the app and the floating card over other apps use the same words and look. While Android's installer takes over an update, the card says "Almost done" with no button.
**Why:** the owner found the green look and the jargon off-putting for people who are not developers.
**Risk:** none to safety. Protocol, docs, and gateway messages keep the precise terms.

## ADR-026 — Files, photos, sharing, and asking the owner (Wave 2)
**Status:** accepted (2026-10-04, owner request) · **Amends:** chapter 17 §3 ("no file tools"), stages the chapter 02 Tier 4 and chapter 07 "Files and media" items
**Decision:** three new capabilities, each off by default, and one new tool that needs none:
- `file.read`: list and read **photos and videos the owner allowed** (Android's photo permission; on Android 14+ "selected photos only") and files inside **one folder the owner picked** with Android's folder picker (Storage Access Framework). Items are addressed by opaque ids the phone issues, never by paths, so nothing outside those scopes can be named. Text up to 256 KB is returned as text; images are downscaled to fit 512 KB; other types return metadata only.
- `file.write`: create, overwrite, rename, and delete inside the picked folder only, up to 512 KB per file, with an allowlist of text, image, PDF, and common document types. Overwrite and delete ask the owner every time unless Auto mode is on (ADR-024).
- `app.share`: `share_to_app` opens an app's Android share screen with chosen items (for example, three photos into Instagram). The share sheet only prepares the post; publishing is a tap that follows ADR-021 and ADR-022.
- `ask_owner` (no capability): the AI asks the owner to do something only a person should do (log in, unlock, type a code, pick something); a card over the current app shows the message with "Done" and "I can't", and the tool returns the answer. It has no timeout beyond the gateway's approval wait and cannot be answered by the AI.
**Never:** "all files" access, paths, the app's own or other apps' private storage, executing or opening files as code, reading files outside the picked folder or allowed photos, or file contents in the gateway audit, Activity, logs, telemetry, or errors. Activity shows the action and the file name only on the phone.
**Why:** the owner's main use case is "post the photos in my ToPost folder to Instagram": through the share sheet that takes about four calls instead of thirty taps through a gallery picker, and it is more reliable.
**Risk:** file contents reach the AI and pass through the owner's gateway. Mitigations: off by default, owner-picked scope revocable in the app (and by Android), size and type limits, opaque ids, content never logged, Stop and session expiry. Prompt injection inside a file is untrusted data like screen text.
**Reversal trigger:** a way to name or read anything outside the picked folder or allowed photos, or file contents found in any log.
