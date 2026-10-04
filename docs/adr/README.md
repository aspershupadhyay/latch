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
- `file.read`: list and read **photos and videos the owner allowed** (Android's photo permission; on Android 14+ "selected photos only"), files Latch saved in **Download**, and files inside **one folder the owner picked** with Android's folder picker (Storage Access Framework). Items are addressed by opaque ids the phone issues, never by paths, so nothing outside those scopes can be named. `read_file` shows text up to 64 KB or a downscaled image.
- `file.write`: save new files in the picked folder (any type, with subfolders), in `Download/<subfolder>`, or as pictures and videos in `Pictures/<subfolder>` (they appear in the gallery); rename and delete there. In photos and Download only files Latch saved may be changed. Overwrite and delete ask the owner every time unless Auto mode is on (ADR-024); "Ask me before every action" asks for every change.
- **Moving whole files between the AI's computer and the phone** (owner request): `get_file_link` copies a phone file to the owner's gateway and returns a private download link; `upload_link` returns a private upload link whose file `write_file` then saves on the phone. Links are 256-bit tokens, live 15 minutes, carry up to 64 MB (4 MB on Vercel, whose functions take 4.5 MB per request), and are kept in gateway memory or Redis only. The bytes never pass through the AI's context, so a desktop agent saves files with `curl`. The phone sees files in 512 KB chunks.
- `app.share`: `share_to_app` opens an app's Android share screen with chosen items (for example, three photos into Instagram). The share sheet only prepares the post; publishing is a tap that follows ADR-021 and ADR-022.
- `ask_owner` (no capability): the AI asks the owner to do something only a person should do (log in, unlock, type a code, pick something); a card over the current app shows the message with "Done" and "I can't", and the tool returns the answer. It has no timeout beyond the gateway's approval wait and cannot be answered by the AI.
**Never:** "all files" access, paths, the app's own or other apps' private storage, executing or opening files as code, reading files outside the picked folder or allowed photos, or file contents in the gateway audit, Activity, logs, telemetry, or errors. Activity shows the action and the file name only on the phone.
**Why:** the owner's use cases: "post the photos in my ToPost folder" to any app (Instagram, YouTube, X, LinkedIn, ...) through the share sheet in about four calls instead of thirty taps through a gallery picker, and moving files both ways between the computer and the phone.
**Risk:** file contents reach the AI and pass through the owner's gateway. Mitigations: off by default, owner-picked scope revocable in the app (and by Android), size and type limits, opaque ids, content never logged, Stop and session expiry. Prompt injection inside a file is untrusted data like screen text.
**Reversal trigger:** a way to name or read anything outside the picked folder or allowed photos, or file contents found in any log.

## ADR-027 — Encrypted file links of any size, the clipboard, and a cursor that stays (protocol 1.7)
**Status:** accepted (2026-10-04, owner request: "at least 1 GB, no compression, encrypted so no middleman can read it") · **Amends:** ADR-026 (links of 4 MB on Vercel, 64 MB self-hosted, bytes through command frames)
**Decision:**
- Whole files move by link without passing through a command frame or a Vercel function: the phone downloads (`file.fetch`) or uploads (`file.push`) the bytes itself, in the background, and `file.transfer` follows progress; the AI's computer uploads and downloads with one shell command (openssl + curl) from the tool answer. Storage: Vercel Blob (private store, signed one-file URLs, 15 minutes) when connected, else the gateway itself (`/v1/blobs/{token}`: Redis up to 4 MB on Vercel, a private temp folder up to `LATCH_MAX_TRANSFER_MB`, default 2 GB, self-hosted).
- **Encryption between the owner's devices:** the stored copy is always AES-256-CTR ciphertext under a key made for that one transfer (derived from the owner key on Vercel, random in memory self-hosted) that is never stored with the file or in Redis. The plain file's SHA-256 travels separately: the phone keeps a download only if it matches (written to a hidden pending file until then); the computer's command verifies its download the same way. Bytes are never re-encoded or compressed.
- The phone follows links only to its own gateway's origin and Vercel Blob's hosts, never through redirects; link headers are limited to storage hints (`x-…`, `content-type`).
- `clipboard.write` (off by default): `set_clipboard` puts text on the clipboard, e.g. a caption an app drops from the share sheet. Reading the clipboard is not offered.
- The cursor shows off-screen work ("Saving “clip.mp4”", a progress bar) and stays, "Thinking…", for 45 s after the AI's last command.
**Never:** keys in logs, Redis, or storage; links to other hosts; reading the clipboard; file contents in logs or the audit.
**Why:** the owner's request (1 GB+, full quality, no readable copy at any middleman) and the phone test (Instagram ignores shared captions; the owner could not tell the AI was working between actions).
**Risk:** the key reaches the AI app (it runs the command) and the owner's gateway; the gateway is the owner's own, and the AI app already sees the file on the computer. CTR is malleable, so integrity rests on the SHA-256, which travels over the authenticated MCP and device channels. Vercel Blob's signed-upload headers are mirrored from `@vercel/blob` 2.8 (not a documented curl interface): if Vercel changes them, only the gateway needs updating. A 1.6 phone keeps the old, unencrypted 4 MB / 64 MB links until it updates.
**Reversal trigger:** a key found in storage or logs, a phone following a link to another host, or Vercel removing signed URLs.

## ADR-028 — Crash-proof commands, an Activity log that is kept, several folders, and finish_task (protocol 1.8)
**Status:** accepted (2026-10-04, owner request after a phone run in which Latch closed mid-task and Android switched its accessibility service off) · **Amends:** ADR-026 (one picked folder), ADR-027 (cursor that stays 45 s), chapter 17 §5 ("Activity: in-memory timeline")
**Decision:**
- Every phone command runs off the main thread, and no failure inside a command can close the app: it becomes an `internal` error for the AI and a line in Activity. A failure that would still close Latch is recorded as its kind and code location only, and shown on the next start.
- **Activity is kept on the phone for 7 days** (app-private storage, never backed up, at most 2,000 lines), in ten kinds: session, connection, screen, action, app, approval, refusal, file, folder, task. Lines hold what happened, the app it happened in, and file or app names; never screen text, typed text, or file contents. "Clear activity" deletes it.
- Protocol 1.8: capability `activity.read` (off by default) and `activity.list {limit 1–500 (50), kinds?, since_ms?}` → `ActivityList {entries: [{at_ms, kind, summary, app?}], total}`, MCP tool `get_activity`; and `task.done {summary? ≤ 500}` (no capability), MCP tool `finish_task`, which hides the cursor at once and logs the AI's summary. Server instructions ask agents to call it when they finish.
- The cursor says what the running command does, "Waiting for your answer" while the owner is asked, then "Waiting for your AI" for 6 s, and fades.
- The Stop pill is a third of its old width, starts on the right edge below the middle, never appears in screenshots for the AI, and moves aside when an AI gesture would start on it. A tap still stops everything.
- Files: the owner may share **several folders** (up to 20). With more than one, the top level of `location: folder` lists the folders and the AI names one by id. Every id is checked against the folders shared at that moment. Photos get Latch's own switch on top of Android's permission.
**Never:** screen or typed text, or file contents, in the kept Activity; the AI pressing Stop; an id reaching outside the shared folders.
**Why:** the owner's report (crash, accessibility off, cursor label off screen, "Thinking…" while nothing happened, the pill in the way) and requests (every action transparent and readable from the AI app, more than one folder, a way to turn photos off).
**Risk:** a kept log is readable by anyone who unlocks the phone and opens Latch; it holds app and file names. Mitigations: app-private, not backed up, 7 days, Clear. `activity.read` gives the AI app those names, so it is off by default.
**Reversal trigger:** screen or typed content found in the kept log, or the pill moving away from a touch by the owner.

## ADR-029 — The gateway is private: no web console, noindex everywhere
**Status:** accepted (2026-10-04, owner request: "only MCP and the app may use the gateway; nobody else can browse it or index it, and why is there a key box on the page?") · **Amends:** chapter 17 §2 and §5 (owner console at `/`), ADR-020 (console layout), S5 (OAuth approval "in the app or with the owner key")
**Decision:**
- Both gateways serve one static page at `/` (`servers/mcp/src/page/`, copied to `servers/vercel/public/` by `scripts/sync-vercel.sh`): it says the address is private, tells a freshly deployed owner to paste the address into the app, and has no scripts (`script-src 'none'`), forms, inputs, or data. The owner console is removed; phones, pairing codes, AI keys, and OAuth approvals are managed in the Latch app, which already does all of it through `/v1/admin/*` with the owner key (curl works too).
- The OAuth consent page can only deny; approval happens only in the Latch app. A posted owner key approves nothing.
- Every answer from both gateways, found or not, carries `X-Robots-Tag: noindex, nofollow, noarchive, nosnippet, noimageindex`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, HSTS (2 years), and a `Permissions-Policy` that turns off camera, microphone, location, payment, and USB. `vercel.json` repeats them for `public/`. `/robots.txt` disallows everything except `/` (so crawlers can read its noindex).
- `/healthz` no longer reports `devices_connected`: an anonymous visitor should not learn when the owner's phone is online.
**Never:** a page that asks for the owner key; gateway data on an unauthenticated page.
**Why:** typing the master key into a web page is the easiest thing to phish, and a public console invites guessing and scraping. The app already holds the key.
**Risk:** owners without the app (self-hosted, scripted) lose the browser console; the owner API and `scripts/dev.sh` cover them. noindex and an unlisted address are not access control: `*.vercel.app` names appear in certificate-transparency logs, so security still rests on 256-bit keys stored as hashes, single-use pairing codes with a failure lock, and constant-time comparison. A per-IP lockout on wrong owner keys was rejected: guessing a 256-bit key is impossible, and a lockout would let anyone lock the owner out.
**Reversal trigger:** an owner task that needs a browser and cannot be done from the app or the owner API.

## ADR-030 — AGPL-3.0 with attribution terms and a name policy
**Status:** accepted (2026-10-04, owner request: "the current license is too permissive; this code can be misused; anyone who uses it must publish their code and keep our name on it") · **Amends:** chapter 14 license recommendation (Apache-2.0)
**Decision:**
- The code is licensed under the **GNU AGPL-3.0-only** (`LICENSE`). Distributing Latch or a modified version requires publishing the complete source under the same license; running a modified version as a network service (a gateway) requires offering its users that source (section 13).
- `NOTICE` adds terms allowed by AGPL section 7: keep NOTICE, the per-file SPDX and copyright lines, and a visible "Based on Latch by Aspersh Upadhyay" credit (7(b)); mark modified versions (7(c)); no right to the name or logo (7(e)).
- `TRADEMARKS.md` reserves the name "Latch" and the hook-and-pin logo: forks must rebrand.
- Every source file carries `SPDX-License-Identifier: AGPL-3.0-only` and a copyright line; `scripts/headers.py --check` runs in CI.
- Contributions: inbound AGPL plus permission for the owner to relicense (`CONTRIBUTING.md`), so the owner can still change terms or grant exceptions.
- The app's Settings and the gateway page link to the source and name the license (AGPL section 5(d) and 13).
**Why:** Latch operates people's phones. A permissive license lets anyone ship a closed, modified build (for example one that hides its data flows) under any name. AGPL keeps every derivative inspectable, and the name policy stops look-alikes from trading on the project's trust.
**Limits (stated plainly):** no open-source license can forbid others from publishing their own version; AGPL only forces it to stay open, credited, and differently named. A license that forbids forks would not be open source. Notices cannot technically stop a person or an AI tool from removing them; they make removal a clear license violation. Releases before this change stay available under Apache-2.0.
**Reversal trigger:** the owner chooses a different license, or a dependency's license conflicts with AGPL-3.0.
