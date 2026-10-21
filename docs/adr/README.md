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

## ADR-031 — Skills: do a task once, run it again with new inputs
**Status:** accepted (2026-10-04, owner request: "record once, replay forever, but smart: today bread and paneer, tomorrow ten other things") · **Amends:** chapter 17 §3 (tool surface)
**Decision:**
- Four MCP tools on both gateways: `save_skill`, `list_skills`, `run_skill`, `delete_skill`. A skill is a recipe saved on the owner's gateway (Redis on Vercel, `devices.json` self-hosted), at most 50 per gateway.
- Steps name elements by their words, never by coordinates or element ids: `launch_app`, `tap {target, near?, long_press?, repeat?}`, `type {target?, text, submit?}`, `scroll_to`, `wait_for`, `press`, `ask_owner`. Params are text, number, or list (with fields); `for_each` repeats a block per list item; templates `{param}`, `{item.field}`, `{item.qty-1}`. `near` picks the match closest to the row showing other words ("ADD" near "{item.name}"), ignoring text fields that echo the search; `"ADD || +"` accepts alternatives; `optional` skips pop-ups.
- `run_skill` runs on the gateway, one ordinary tool call per step, so app permissions, approvals, freshness, refused fields, Stop, and the audit apply exactly as if the AI had called each tool itself. A step that cannot be matched stops the run and returns the live screen; the AI does that step and continues with `from_step`. One call does at most 60 phone actions and 240 s, then pauses with a `from_step`.
- The engine (validation, expansion, matching) is written twice and held identical by shared cases in `packages/schemas/v1/skills` (expected output generated by the Rust tests, checked by the TypeScript tests). The fake phone gained a Shop app for end-to-end tests on both gateways.
**Never:** a skill that skips the owner's approval, holds a password, PIN, code, or card number on purpose (the tool text forbids it, and the phone still refuses those fields), or runs without an AI client asking for it.
**Why:** repeated tasks took 20–40 tool calls each, every time, at 5–11 s per action; a skill turns the steady part into one call while the AI still handles what is new.
**Risk:** a skill written by one AI app is run by another; its texts are shown as data, not instructions. A wrong `near` could pick a neighbouring row; the run returns the final screen and the AI is told to check it before reporting success. No skill can do more than the AI could do with the same tools.
**Reversal trigger:** a skill run that acts without the approvals the same single tool calls would need.

## ADR-032 — Reading unlabeled buttons on the phone with Google ML Kit
**Status:** accepted (2026-10-15, owner: "yes go ahead with ml kit and the exception") · **Amends:** ADR-030 (adds an AGPL section 7 permission) · roadmap R9
**Problem:** the phone's tap check (`policy/Consequences.kt`) decides from labels whether a tap needs the owner. An icon-only Send or Pay button with no text, description, or labeled ancestor looked harmless, so in an app the owner had not switched on it could run without a question (§6, "coordinate taps on unlabeled controls are a known gap").
**Decision:**
- When nothing a tap would hit carries a label (`Consequences.needsReading`), the phone captures the pixels of that control, or a 192 px square around the tap when the target is page-sized or missing, and reads its words with Google ML Kit's bundled, on-device text recognition (`accessibility/ButtonReader.kt`, `com.google.mlkit:text-recognition` 16.0.1).
- The words feed the same word lists as labels and can only **raise** the judgement (`judgeTap(seen = …)`). They never appear in the approval title, the remember key, the Activity log, the gateway, or the AI: those can reach the AI app, and what was read is screen content.
- Owner switch **Settings → Read unlabeled buttons**, on by default, with the data note in its description. ML Kit's start-up provider is removed from the manifest and ML Kit starts only on first use, so with the switch off it never runs and sends nothing.
- A failure (protected window, timeout of 1.5 s, no text) leaves the judgement as it was without reading: never less strict than before this change.
- **License:** NOTICE adds an AGPL section 7 additional permission (d) to combine the Android app with Google ML Kit and the Play services libraries it needs. Everything else stays AGPL-3.0 with the existing terms. The owner may grant it because `CONTRIBUTING.md` gives the owner relicensing rights over contributions.
**Data:** ML Kit processes the picture on the phone and does not send it or the words to Google ([ML Kit terms](https://developers.google.com/ml-kit/terms)). It does send Google usage metrics: device model and OS, app package and version, ML Kit version, latency, input size, event and error codes, and a per-installation identifier ([Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)). Google's terms make the app responsible for telling users; the switch description does, and so do README and `docs/platform/android.md`. There is no supported way to turn the metrics off other than not running ML Kit.
**Cost:** about 4 MB more per ABI in the APK; one extra screen capture (tens of ms, plus up to ~1 s when Android's screenshot rate limit applies) only for taps on unlabeled controls.
**Not chosen:** Tesseract (Apache-2.0, F-Droid friendly) is 15–20 MB larger, slower, and weaker on small icon labels; position-and-size heuristics alone cannot tell a Send icon from a Photos icon.
**Consequences:** the APK with ML Kit cannot be on F-Droid. A future `foss` build flavor without ML Kit (reading off, heuristics only) is the path if F-Droid matters.
**Reversal trigger:** Google changes the ML Kit terms or starts sending image or text data; an F-Droid release becomes a priority; or a permissively licensed on-device reader matches ML Kit's size and accuracy.

## ADR-033 — run_steps: several predictable steps in one call
**Status:** accepted (2026-10-15, roadmap R4, owner: "keep going with the next roadmap item") · **Amends:** chapter 17 §3 (tool surface), ADR-031
**Problem:** every step of a task costs an AI turn plus a phone round trip, and the AI's own thinking time per turn is usually larger than Latch's. ARTEMIS-style "action bursts" chain steps without a model turn in between. §8 deferred compound actions until they had their own threat review.
**Decision:** a new MCP tool `run_steps` on both gateways takes a list of steps in exactly the skill step format (`launch_app`, `tap`, `type`, `scroll_to`, `wait_for`, `press`, `ask_owner`; no params) and runs them in one call, without saving anything. It is the skill engine of ADR-031 with an inline recipe: validated and expanded by the same code (same limits: 50 top-level steps, 60 phone actions and 240 s per call), executed one ordinary tool call per step on the gateway.
**Threat review:** no new capability or path. Each step is the same tool call the AI could make itself, so app permissions, the gateway policy, the phone's own judgement (including ADR-032 reading), approvals, freshness (the gateway observes and cites the latest observation for each element step), refused fields, Stop, pause, and the audit apply per step. Steps are matched by their words on the live screen, never by stale coordinates or ids. A step that does not match stops the run and returns the screen; nothing continues blindly. Screen text never selects the next step: the AI wrote all steps before the call.
**Not chosen (yet):** a single phone command that runs several actions on the phone without a gateway round trip in between. It would save more time but needs a protocol change and a phone-side policy loop; revisit when R1 shows the gateway round trip, not the model turn, dominates.
**Reversal trigger:** a `run_steps` run that acts without an approval the same single tool calls would need.

## ADR-034 — After an action, show the AI only what changed
**Status:** accepted (2026-10-15, roadmap R5, owner: "keep going with the next roadmap item") · **Amends:** chapter 17 §3 (observation rendering)
**Problem:** every action answers with the whole screen (up to 400 elements). Most actions change a few lines (a switch, a toast, a counter), but the AI reads and pays for the whole list each time, which is slow and costly in tokens.
**Decision:** the gateways, not the phone, render an action's screen as the changes since the last screen the AI was shown in full (`render_changes` / `renderChanges`, held byte-identical by `packages/schemas/v1/mcp/changes-{input.json,expected.txt}`) when: the same app is in front on the same screen size, at least 8 shown elements kept their id and line, and at most 40 % of lines changed, appeared, or left. Changed and new elements are listed in full; ids that left are named under "Gone". Otherwise the screen is shown in full and becomes the new base. An explicit `observe` is always in full. The base is per phone connection (Rust: in memory; Vercel: Redis, 10 minutes, without screenshot) and a new connection starts without one.
**Why ids are safe:** an element counts as unchanged only when its id **and** its whole rendered line are equal, so an id the AI remembers from the full screen still names the same element in the new observation; the phone's own freshness check (same app, element present, within 8 px) still runs on every action.
**No protocol change:** the phone sends full observations as before; screenshots are unaffected.
**Cost:** the Vercel gateway reads the base with one extra Redis round trip per action, and writes it when a screen is shown in full.
**Reversal trigger:** an AI acting on a stale or wrong element because of a partial screen, or partial screens confusing agents in the R10 phone runs.

## ADR-035 — Phone controls: volume, media, brightness, flashlight, settings panels
**Status:** accepted (2026-10-15, roadmap R16, owner: "keep going with the next roadmap item") · **Protocol:** 1.11
**Problem:** turning the volume down or the flashlight on took several taps through Settings or Quick Settings (which Latch keeps off limits), each one slow and easy to miss.
**Decision:** one command, `device.control`, and one MCP tool, `phone_control`, with four groups, each its own capability and **off by default**: `control.sound` (volume per stream, media keys), `control.brightness`, `control.flashlight`, `control.panels` (the Wi-Fi, internet, and volume panels; Bluetooth opens its settings page, as Android has no panel). Public Android APIs only: `AudioManager`, `Settings.System` behind the owner's own "Modify system settings" grant, `CameraManager.setTorchMode`, `Settings.Panel`. Nothing is read back from the phone.
**Safety:** policy rates every control medium, like a plain tap, so "Ask me before every action" covers them; the phone logs each one in Activity in the owner's words (`PhoneControl::describe`, the same text on both gateways and the phone). A panel only opens: switching Wi-Fi or Bluetooth inside it is an ordinary tap in the Settings app, which must be switched on and whose risky buttons still ask. Not offered: airplane mode, mobile data, location, Do Not Disturb, and any toggle without a panel, because Android reserves them for system apps or they cut the phone off from its own gateway. Ring and notification changes Android refuses under Do Not Disturb come back as `policy_refused`.
**Reversal trigger:** an AI using controls to disturb or surprise the owner (e.g. volume at night) in a way the per-group switches and Activity do not make visible.

## ADR-036 — Limits per AI app: which phone apps, and how many calls
**Status:** accepted (2026-10-15, roadmap R18, S5; owner: "keep going with the next roadmap item")
**Problem:** the phone's app switches (ADR-021) are one list for every AI app. An owner who lets one assistant handle WhatsApp and another only Settings had no way to keep the second out of WhatsApp, and one runaway AI app could flood the phone with calls.
**Decision:**
- Each AI app (a key or an OAuth app) may carry `apps`, a list of package names, set by the owner through `/v1/admin/clients` (create or `PATCH`, `null` lifts it). The gateways enforce it in the shared policy (`DeviceContext.allowed_apps` / `allowedApps`): `app.launch` and `app.share` only to listed apps, and any action citing a screen only when that screen's app is listed; refused as `policy_refused` before the phone is asked. Any screen that comes back from an unlisted app (an observation, a `wait_for`, an action's result) is withheld with `policy_refused`, so the AI app never reads it. Pressing Home or Back, files, the clipboard, phone controls, and asking the owner are not app-bound and stay as the owner's other switches allow. The phone's own switches still apply on top; `LATCH_MCP_TOKEN` has no limit.
- Each AI app has its own budget of tool calls per minute (`LATCH_CLIENT_CALLS_PER_MINUTE`, default 120, 0 for none): over it, the call answers `rate_limited: …` as a tool error, without contacting the phone; other AI apps are unaffected.
**Why in the gateway:** the gateway is where AI apps are told apart; the phone sees one gateway. Both gateways use the same policy cases (`packages/schemas/v1/policy/cases.json`, `client_apps`) and the same texts.
**In the app:** Connect → tap an AI app → *Any app I switched on* or *Only the apps I pick* (searchable list of this phone's apps; apps already in a limit stay listed after they are uninstalled).
**Reversal trigger:** owners finding the limit confusing next to the phone's own switches, or a legitimate agent hitting the default budget in normal use.

## ADR-037 — Device keys: every message from a phone is signed
**Status:** accepted (2026-10-15, roadmap R19, S5; owner: "keep going with the next roadmap item") · **Amends:** ADR-014 ("Next step: device-held keys signing results") · **Protocol:** 1.12
**Problem:** a phone proves itself with a bearer token over TLS. Anyone who copies that token (a backup, a leaked log, a compromised proxy) can speak for the phone: forge screens, results, and approval requests.
**Decision:**
- At first use of a pairing the app makes a P-256 key in the Android keystore (`KeystoreSigner`, alias per pairing, deleted when the pairing is forgotten). The private key never leaves the keystore.
- Every message from a 1.12 phone travels as `{"type":"signed","payload":"<message JSON>","sig":"<base64 DER>"}`: ECDSA P-256 / SHA-256 over `latch-signed-v1\n<connection>\n<payload>`, where `<connection>` is the id the gateway named in its welcome (empty for `hello`). Signing the exact payload text avoids any JSON canonicalisation; binding the connection stops a message from being replayed into another connection.
- The first signed `hello` carries `device_key` and registers it (trust on first use, inside the already authenticated channel). From then on the gateway refuses an unsigned message, a hello with a different key ("pair it again"), and any signature that does not verify. Phones before 1.12 keep working unsigned; the app signs only after `/v1/info` says the gateway speaks 1.12.
- Standard primitives only: Android keystore + JCA on the phone, RustCrypto `p256` on the container gateway, Node's built-in crypto on Vercel. High- and low-S signatures both verify (the keystore does not normalise).
**Checks:** the shared vector `packages/schemas/v1/signing/vectors.json` verifies in Rust, TS, and the Android JVM tests; e2e on both gateways refuses a stolen token alone, a thief's own key, a key without proof, an unsigned message, and a replay into another connection; the app's real `DeviceLink` runs signed against both gateways.
**Not covered:** a phone whose keystore is compromised; the first pairing itself (still the single-use code). WebSocket phones (the fake phone) sign the same way.
**Reversal trigger:** keystore failures on real phones that the unsigned fallback cannot cover, or a measurable latency cost per message.


## ADR-038 — Owner-key rotation from the app, recovery through LATCH_ADMIN_TOKEN
**Status:** accepted (2026-10-15, roadmap R19, S5; owner: "finish R19: the owner-key rotation flow")
**Problem:** the owner key (`LATCH_ADMIN_TOKEN`) controls pairing, revocation, and AI-app keys. Changing it meant editing the gateway's settings, redeploying, and pairing the phone again, so in practice nobody rotated it, even after it was copied somewhere it should not be.
**Decision:**
- `POST /v1/admin/owner-key` (owner key required) issues a new 256-bit key (`lok_…`), shown only in that answer (`cache-control: no-store`). From that moment only the new key opens `/v1/admin/*`; the key used to ask, `LATCH_ADMIN_TOKEN` included, is refused. Rotating again ends the previous issued key the same way.
- The gateway keeps only `SHA-256(new key)`, `SHA-256(LATCH_ADMIN_TOKEN at the time)`, and the time (Rust: `devices.json`; Vercel: `latch:ownerkey` in Redis). It never stores either key.
- **Recovery:** the issued key is honoured only while `LATCH_ADMIN_TOKEN` is the token it was issued under. An owner who lost the key sets a new `LATCH_ADMIN_TOKEN` and restarts or redeploys; the new token works, and its first use forgets the issued key, so returning to an old token later never brings an old key back. Whoever can change the gateway's environment already controls the gateway, so this grants nothing new.
- Android: **Settings → Rotate owner key** (shown only on the phone that set the gateway up) confirms, rotates, and saves the new key in the keystore-backed vault; the owner can copy it once as a backup. If saving fails, the dialog says so and offers the key to copy, and the recovery path still applies.
- Phones, AI-app keys, OAuth apps, and file links are unaffected (the Vercel gateway still derives link keys from `LATCH_ADMIN_TOKEN`, which a rotation does not change).
**Checks:** e2e on both gateways (`the_owner_key_rotates_and_a_new_admin_token_recovers_access`, `servers/vercel/test/owner-key.test.ts`): refused without or with a non-owner key, old keys refused after each rotation, the new key survives a restart, only hashes on disk, recovery with a new token, no return of the old key; the app's `OwnerClient` against both gateways (`rotatingTheOwnerKeyEndsTheOldOne`).
**Not covered:** a second phone that also holds the owner key keeps the old one and is refused until it connects again with the new key.
**Reversal trigger:** owners locking themselves out without understanding the recovery step, or a platform where the environment cannot be changed by the owner.

## ADR-039 — Screen content end to end: threat review, and sealing it from the database
**Status:** accepted (2026-10-15, roadmap R20; owner: "continue with R20") · **Answers:** ADR-011's reversal trigger ("a transport that gives end-to-end encryption between phone and MCP client without a trusted gateway") and the chapter 17 §6 risk "the cloud gateway sees screen content in transit"
**Threat review: who sees a screen today.** A screen travels phone → gateway → MCP client → the AI model. Over TLS the parties are:
| Party | Sees screen content | Why |
|---|---|---|
| The AI provider's MCP client and model | Yes, always | The model must read the screen to act. No encryption can hide content from the party that has to understand it. |
| The gateway process (Vercel function or the owner's container) | Yes | It applies rules that need content: an AI app's phone-app limit withholds screens from unlisted apps (ADR-036), refused fields, screen diffs (ADR-034), skills and `run_steps` match steps by their words (ADR-031, ADR-033), `wait_for`/`scroll_to` look for text. |
| The hosting platform (Vercel, the container host) | Could, as operator of the process | Same as any server code. Self-hosting removes it. |
| **The database (Upstash on Vercel)** | **Yes, until this ADR:** observations, results with screenshots, approval summaries, queued commands with typed text, and answers to the owner's questions were stored in plaintext for seconds to 10 minutes | Vercel functions are stateless, so the gateway passes these through Redis. A separate company and a separate credential (the Upstash token) that the owner shares with integrations and dashboards. |
| Network observers | No | TLS on every hop. |
**Option rejected (for now): phone ↔ AI-client end-to-end encryption.** Hiding content from the gateway would mean (a) a component on the AI side that holds a key and decrypts, which for remote clients (claude.ai, ChatGPT, Codex cloud) does not exist and for local clients means a Latch program running on a computer, against the "no laptop daemon" direction; and (b) moving every content-based rule above out of the gateway, into that component or the phone, so a broken or hostile client would also be the only enforcer of the AI-app limits. It would protect against the gateway's host only, which self-hosting already removes, and it cannot protect against the AI provider, which must see the screen. The cost (a second enforcement point, a key exchange, a new client to install) is far larger than the gain.
**Decision:** take out the one party that does not need to see content: the database.
- The Vercel gateway seals every value under `latch:q:`, `latch:result:`, `latch:approval:`, `latch:obs:`, `latch:base:`, and `latch:elicit:` with AES-256-GCM before it leaves the function (`servers/vercel/src/sealed.ts`, a wrapper around the store). The key comes from HKDF-SHA256 over `LATCH_ADMIN_TOKEN` (salt `latch store v1`), which only the gateway's environment holds; the Redis key name is the associated data, so a value moved to another key (another phone, another command) does not open, and a value that does not open is treated as gone (a timeout, never a wrong screen).
- Unsealed values from before an upgrade are still read and expire on their own. Locks, counters, records (hashes only), and the audit (no content) are not sealed: the gateway compares or counts them, and they hold no content.
- File links: protocol 1.7 links are already encrypted between phone and computer with a key in the link (ADR-027); the older 1.6 links (`latch:blob:`) for phones before 1.7 stay as they are, since sealing their 512 KB parts would push them past common Redis value limits.
- Changing `LATCH_ADMIN_TOKEN` (ADR-038 recovery) makes values in flight unreadable; they are short-lived, so at worst a running command times out.
- The Rust container gateway keeps all of this in its own memory and needs no change.
**Checks:** `servers/vercel/test/sealed.test.ts` (every write and read path, other keys untouched, wrong key name, wrong gateway key, tampering, truncation, old plaintext, fresh nonces); the Vercel e2e records every value written during a full session with the fake phone and fails if any content value is unsealed or if screen text or typed text (`Wi-Fi`, `hunter2`, …) appears in any stored value. The check fails with sealing turned off. CI also runs it against a real Redis.
**Not covered:** the gateway process and its host; the AI provider. Self-hosting the container gateway remains the answer for the first; the owner's choice of AI app for the second.
**Reversal trigger:** an MCP client standard for end-to-end encrypted tool results with a client-held key, or owners asking for a local-only mode where the computer is trusted and the gateway is not; then revisit option (a) with the rules moved to the phone.

## ADR-040 — The phone reports battery optimisation, and `diagnose` passes on the fix
**Status:** accepted (2026-10-15, roadmap R13; owner: "work on the remaining roadmap parts") · **Protocol:** 1.13
**Problem:** the commonest reason a phone drops off mid-task is Android stopping Latch in the background under battery optimisation. `diagnose` could only guess at it when a phone was already offline, so an agent could not warn the owner while the phone still worked.
**Decision:** the hello carries an optional `battery_restricted` boolean, read on the phone from `PowerManager.isIgnoringBatteryOptimizations` (true when Latch is not exempt). Both gateways keep it with the live connection (Rust `LiveSummary`, Vercel `Live`) and `diagnose` adds, for that phone, "Android's battery optimisation is on for Latch… Fix: Android Settings → Apps → Latch → Battery → Unrestricted." It is a warning, not a blocker: the phone stays "Ready". The text is in the shared fixture (`packages/schemas/v1/mcp/diagnose-*`), byte-identical in Rust and TypeScript.
**Why a hello field:** it is one bit about the phone's own app, holds no screen or personal content, and changes rarely; a reconnect refreshes it. Older gateways ignore unknown hello fields, so phones send it to any gateway.
**Not covered:** whether the accessibility service is on needs no new field: switched-on capabilities already report `needs_permission` without it, which `diagnose` names. Brand-specific background killers (some vendors stop apps despite the exemption) are not visible to the app.
**Reversal trigger:** the field turning out to be wrong on common phones (for example, vendor battery managers that report exemption but still stop the app).

## ADR-041 — Same-Wi-Fi fast lane for whole files
> **2026-10-17 update:** the ADB rejection below is historical and superseded by [ADR-045](#adr-045--owner-authorized-adb-shell-through-mcp). This ADR's existing implementation remains supported; it is not a current ban on opt-in ADB.
**Status:** accepted (2026-10-16, roadmap R23; owner: "yes add R23"); built in the protocol, both gateways, and the Android app · **Protocol:** 1.14 · **Builds on:** ADR-027 (files by encrypted link)
**Problem:** a whole file between the owner's computer and phone travels computer → storage → phone (ADR-027), so it moves at the slower of the two internet uploads, twice. On the same Wi-Fi the two devices could talk directly, typically 20–80 MB/s on home Wi-Fi, so a 1 GB video takes seconds instead of minutes. AirDrop gets its speed this way (local discovery plus a direct peer-to-peer link).
**Options considered:**
| Option | Verdict |
|---|---|
| ADB over Wi-Fi (as ADB agents use) | Rejected: wireless debugging gives the computer a full shell on the phone, which chapter 08 forbids exposing, and it needs developer options |
| Wi-Fi Direct / Wi-Fi Aware / Quick Share | Rejected for now: computers cannot use them from an ordinary program, and Quick Share's protocol is not public |
| The computer runs a small server and the phone downloads from it | Rejected: the phone would have to follow links to arbitrary LAN addresses (today it follows only the gateway and the storage hosts), and release builds refuse plain HTTP |
| **The phone listens on the local network for one transfer, and the computer sends to or reads from it** | **Chosen:** the computer needs only `curl`, as today; the phone never connects anywhere new |
**Decision:**
- An owner switch, **Access → Files → Fast transfers on the same Wi-Fi**, off by default. While it is off, nothing changes.
- When `upload_link` or `get_file_link` is called and the switch is on, the gateway asks the phone to open a **one-transfer listener**: a random port on the Wi-Fi interface only, a 256-bit random path, alive until the transfer finishes or 15 minutes pass, then closed. The phone answers with its local address; the gateway hands the AI a command that tries the local address first with a 2-second connect timeout and falls back to today's cloud link.
- What travels is exactly what ADR-027 sends: AES-256-CTR ciphertext under a key made for that one file, and a file is kept only if its SHA-256 matches. The listener accepts one request on its exact path and ignores everything else.
- The Activity log records each local transfer (name, size, direction), never contents.
**Mechanism (protocol 1.14):**
- `file.local {direction: receive|send, max_bytes}` asks the phone for an address; it needs the capability the transfer itself needs (`file.write` to receive, `file.read` to send) and is not an action. The phone answers `LocalSlot {url, expires_at_ms}`, or `unsupported_capability` when the switch is off, it is not on Wi-Fi, or the app is older. Any error simply means the cloud link.
- Gateways accept only `http://<private, loopback, or link-local IPv4>:<port ≥ 1024>/<64 lowercase hex>` (`validate::local_slot`, `isLocalSlotUrl`), so a phone can never point the AI's computer at an internet address.
- **Upload:** `upload_link`'s command encrypts the file once to a temporary file, `curl --connect-timeout 2 -T` it to the phone's address, and on any failure to the cloud link with the same key. `write_file` then hands the phone a `file.fetch` whose link URL is its own address; the phone reads what arrived there instead of fetching. When the gateway holds a copy (the Wi-Fi try failed), or nothing arrived on the Wi-Fi (`target_not_found`), it uses the cloud link.
- **Download:** `get_file_link` opens a `send` address and sends `file.push` to it; the phone encrypts into it instead of uploading, and the command reads it once. If this computer cannot reach the phone, the command says to call `get_file_link` again with `lane: "cloud"`, which skips the Wi-Fi.
**Threat review:**
| Who | Can they… | Why not, or what limits it |
|---|---|---|
| Another device on the same Wi-Fi | read the file | It sees only ciphertext; the key travels phone ↔ gateway ↔ AI over TLS, never on the LAN |
| | change or replace the file | A changed file fails the SHA-256 check and is deleted |
| | find or guess the listener | A random port and 256-bit path, for one transfer, for at most 15 minutes; wrong paths get 404 and change nothing |
| | slow the transfer down | Possible (a LAN denial of service); the cloud link stays as the fallback |
| A hostile Wi-Fi network (café, office) | anything more | No, by the same three rows; client isolation simply makes the local try fail and the cloud link is used |
| The AI app | reach other files | No: a listener carries only the transfer the existing file rules already allowed (capabilities, folder grant, approval for overwrite) |
| A tampered phone or gateway reply | send the AI's computer somewhere else | Both gateways refuse any address that is not private IPv4 http with a 64-hex path; the computer only ever sends ciphertext |
**Not covered:** devices on different networks or behind client isolation use the cloud link as today. Discovery without the gateway (mDNS) is not needed because the gateway already knows both ends.
**Checks:** done: protocol fixtures (`g2d-command-file-local`, `d2g-result-file-local`, two invalid), address validation tests in Rust and TypeScript, the fake phone runs a real one-transfer listener, Rust e2e `same_wifi_transfers_go_straight_to_the_phone_and_fall_back` (Wi-Fi upload, gateway fallback, nothing sent, Wi-Fi download once, `lane: "cloud"`, switch off), and the Vercel test `wifi.test.ts`, which runs the real `openssl`/`curl` commands from the answers. Android: `LocalSlots` binds to the Wi-Fi (or hotspot) IPv4 address only, never mobile data; its JVM tests (`LocalSlotsTest`) cover the curl-style `Expect: 100-continue` upload, wrong path and method, size limit, one transfer per address, expiry, Stop, a tampered file, and nothing arrived. The emulator job sends a file to the app's Wi-Fi address and reads one back through the real container gateway, with the test playing the computer. Still to do: the speed measured on a real phone and Wi-Fi.
**Reversal trigger:** a common phone or network where opening a local listener is blocked or flagged, or an owner-visible failure the fallback does not hide.

## ADR-042 — The gateway on the owner's own computer, reached over the Wi-Fi
> **2026-10-17 update:** the ADB rejection below is historical and superseded by [ADR-045](#adr-045--owner-authorized-adb-shell-through-mcp). This ADR's existing implementation remains supported; it is not a current ban on opt-in ADB.
**Status:** accepted (2026-10-16, roadmap R24; owner: "make it compatible so that we can connect the MCP server as well through Wi-Fi") · **Protocol:** unchanged (1.14) · **Builds on:** ADR-029 (private gateway), ADR-041 (same-Wi-Fi files)
**Problem:** an AI app on the owner's computer (Claude Code, Claude Desktop, Cursor) can already start the gateway itself (`latch-gateway stdio`), but a phone could reach it only over plain `http://`, which release builds refuse (and should: screen text and the phone's token would cross the Wi-Fi readable). A cloud gateway works, but every action then makes two trips over the internet, and the screen leaves the home.
**Options considered:**
| Option | Verdict |
|---|---|
| Let release builds use `http://` to private addresses | Rejected: anyone on the same Wi-Fi could read the screen and take the phone's token |
| ADB over Wi-Fi | Rejected: wireless debugging gives the computer a full shell on the phone (chapter 08 forbids it) |
| A certificate from a public authority for a local name | Rejected: needs a domain, DNS, and renewal; not something an owner sets up in five minutes |
| **The gateway makes its own certificate; the phone pins its key** | **Chosen:** standard TLS (rustls), no new protocol, nothing to buy or renew |
**Decision:**
- `LATCH_WIFI=1` (or `LATCH_WIFI_BIND=<address>`) adds a TLS listener, by default `0.0.0.0:8788`, serving the same routes. The certificate (ECDSA P-256, made with rcgen) and its key live in `LATCH_DATA_DIR/wifi/`, the key readable by its owner only; they survive restarts, so a paired phone keeps working.
- At start the gateway prints the address to enter in the app: `https://<this computer's Wi-Fi address>:8788#pin=<base64url SHA-256 of the certificate's SubjectPublicKeyInfo>`. The pin is not a secret.
- The app accepts `#pin=` only with `https://`, stores the pin with the pairing, and for that exact host and port trusts that key and nothing else (`GatewayPins`, an `X509ExtendedTrustManager` keyed by host and port). Every other host keeps Android's normal certificate checks, unchanged.
- The AI app on the same computer keeps using the plain local address (`http://127.0.0.1:8787/mcp`, or stdio), which never leaves the computer.
- File links for a phone are built on the address that phone connected through, so they match its paired gateway and pass the app's link check.
**Threat review:**
| Who | Can they… | Why not, or what limits it |
|---|---|---|
| Another device on the Wi-Fi | read screens, commands, or the token | TLS 1.2/1.3 with the pinned key; without the private key there is nothing readable |
| | pretend to be the gateway | The phone refuses any key but the pinned one, also one signed by a public authority |
| | reach the gateway | It still needs the phone's token, the owner key, or an MCP key on every request, as on the internet |
| Someone who copies `LATCH_DATA_DIR` | pretend to be the gateway | Yes, like any server whose private key is stolen; the folder already holds the device list. Delete `wifi/` and re-pair to rotate |
| A typo or a changed pin | connect the phone elsewhere | The handshake fails closed; the app shows the connection error |
**Not covered:** the computer's Wi-Fi address can change (DHCP); the phone then needs the new address (same pin). A phone away from home cannot reach it; use a cloud gateway for that, or both.
**Checks:** Rust: the certificate is made once and keeps its pin, the key file is mode 600, a pinned TLS client gets `/healthz` and requests carry the address used, plain HTTP on the TLS port gets nothing. App (`WifiGatewayTest`, against the real gateway binary): no pin and a wrong pin are both refused; with the pin, pairing, the phone's connection, an AI's `observe` over the local address, and a file link on the Wi-Fi address all work; ordinary https hosts verify as before.
**Reversal trigger:** a phone whose TLS stack does not pass the host to the trust manager (pairing then fails closed), or owners finding the address step too hard.

## ADR-043 — More of what an ADB test agent does, through the phone's own APIs
> **2026-10-17 update:** the ADB rejection below is historical and superseded by [ADR-045](#adr-045--owner-authorized-adb-shell-through-mcp). This ADR's existing implementation remains supported; it is not a current ban on opt-in ADB.
**Status:** accepted (2026-10-16, roadmap R25; owner: "don't give the adb level access, at least give some safe command access that can be done by the artemis") · **Protocol:** 1.15 · **Builds on:** ADR-021 and ADR-022 (owner consent per app), ADR-035 (phone controls)
**Problem:** ADB-tethered agents such as ARTEMIS reach the phone through a shell, so they can press any key, open any app screen by link, read the battery and network, and change settings. Latch reaches the phone only through its accessibility service and ordinary app permissions, so AIs spent several taps on things one call could do, and could not read basic state before a long task.
**Options considered:**
| Option | Verdict |
|---|---|
| Wireless debugging on the phone (ADB rights inside the app) | Rejected by the owner and by chapter 08: a shell-level process on the phone, a far larger blast radius than any one feature needs |
| **The same outcomes through public Android APIs, each a typed command under the existing rules** | **Chosen:** no new privilege, the same policy, approvals, app consent, and Activity log |
**Decision (protocol 1.15):**
- `nav.global` gains `lock_screen`, `screenshot` (Android saves it to the gallery; the AI does not receive it), `split_screen`, and `dpad_up/down/left/right/center` (Android 13+). The notification shade and quick settings are **not** offered: the shade shows one-time codes and message previews, and system UI stays off limits to agents. `screenshot` and `lock_screen` are medium risk (they leave something behind); the rest are low.
- `app.launch` gains `fresh` (start from the app's first screen; `FLAG_ACTIVITY_CLEAR_TASK`, the app's data stays).
- New `app.open_link {url, package}` (capability `app.launch`): `ACTION_VIEW` with `CATEGORY_BROWSABLE` and the package set, so only screens an app offers to links open, and only in that app. URLs are at most 2048 printable ASCII characters with a lowercase scheme; `intent:`, `android-app:`, `file:`, `content:`, `javascript:`, `vbscript:`, `data:`, `blob:`, `about:`, `filesystem:`, and `jar:` are refused by both gateways and the phone. The target app goes through the same app consent as `launch_app` (ADR-021) and the gateway's per-client app list (ADR-036). Payment links (`upi:` and the wallet schemes) are critical and ask every time, like any payment; call, message, and mail links (`tel:`, `sms:`, `mailto:`, `whatsapp:` …) are consequential and ask (rememberable). The phone judges links itself too.
- `device.info` gains optional `status {battery_percent, charging, network: wifi|cellular|ethernet|none, free_storage_mb, orientation, locked, do_not_disturb}`; MCP tool `device_status`. No network names, addresses, or app content.
- `device.control` gains `ringer {normal|vibrate|silent}` and `do_not_disturb {on}` (Sound switch; Android's Do Not Disturb access, granted once by the owner), `rotation {auto|portrait|landscape}` and `screen_timeout {seconds 15–1800}` (Screen switch, formerly Brightness; Android's Modify system settings), and nine more settings pages: NFC, airplane mode, location, display, battery, sound, notification settings, storage, and date and time (Panels).
- Gateways send each new command or value only to phones on 1.15 and later; older phones get a clear version error.
**Threat review:**
| Who | Can they… | Why not, or what limits it |
|---|---|---|
| A tricked AI | open a link that pays or messages someone | Payment links ask every time; message links ask; the phone judges the scheme even if a gateway did not |
| | reach the phone's files or another app's private screen by link | `file:`/`content:`/`intent:` are refused; only BROWSABLE screens of the named app open |
| | open an app the owner never allowed | The same app consent and per-client app list as `launch_app` |
| | read one-time codes from the shade | The shade and quick settings are not buttons; system UI observation stays refused |
| | silence the phone or turn Do Not Disturb on | Only with the Sound switch and Android's own Do Not Disturb access, both off by default; logged |
| | learn where the owner is from status | Status names only the network type, never the network or an address |
**Not covered:** what ADB alone can do (silent installs, granting permissions, turning animations off, system logs) stays out of Latch.
**Checks:** protocol fixtures (6 new valid commands, the device-info status, 7 invalid: intent/file/space/uppercase links, too-short timeout, `power_off`, `notifications`); shared policy cases on both gateways (link schemes, app list, screenshot and lock-screen risk, control capabilities); fake-phone e2e on both gateways (buttons, status lines, links, fresh launch, denied message and payment links, new controls and their errors); Android unit tests (parsing, the phone's link judgement, control texts, status shape).
**Reversal trigger:** a common app whose link screens let an AI act outside the app consent, or owners finding the status or controls surprising.

## ADR-044 — Read more of what Android already tells accessibility services
**Status:** accepted (2026-10-16, roadmap R26; owner: "make it an expert in reading the UI tree … let the AI decide if it needs a screenshot") · **Protocol:** 1.16 · **Builds on:** ADR-013 (screenshots through accessibility), ADR-032 (unlabeled controls read on the phone), ADR-034 (screen diffs)
**Problem:** the element list left out much of what Android reports. Only the focused window was read, so menus, sheets, and dialogs in a second window of the same app were missed, and the AI tapped the screen behind them. State words ("Liked", "Playing"), field hints, the selected tab, headings, a row's place in a list, slider values, and the actions apps attach to an item (Instagram's "Like", Gmail's "Archive") never reached the AI. Icon-only buttons arrived with no name even when their tooltip, the label that names them, or their resource id said what they were. When the AI did take a screenshot, it had to guess which picture region matched which id, and the picture could be newer than the list.
**Options considered:**
| Option | Verdict |
|---|---|
| Declare Latch an accessibility tool (`isAccessibilityTool`) to see what apps hide | Rejected: apps hide sensitive views from non-tools on purpose (ADR-032); pretending would break that trust |
| Send the ML Kit words read from icons (ADR-032) to the AI | Rejected: ADR-032 keeps them on the phone, for approvals only |
| **Read every window of the app in front, the facts Android already exposes, and names from metadata only; let the AI ask for a numbered screenshot** | **Chosen:** no new permission, no screen words leave the phone that Android did not already expose to the service |
**Decision (protocol 1.16):**
- `UiNode` gains optional `state`, `hint`, `selected`, `heading`, `position {index, count?}`, `percent` (0–100), `actions` (the app's own extra actions, ≤ 16 names; standard ones such as click and scroll are left out), `label`, and `covered`. All are omitted when empty; older gateways ignore them.
- `label` is a guess, made on the phone only for an element with no text or description, in this order: its tooltip, the text of the element that labels it (`labeledBy`), then its resource id split into words (`row_feed_button_like` → "row feed button like"). Gateways show it as `label?=` so the AI treats it as a guess. Policy reads it like the other words of an element, which can only add a reason to ask, never remove one; approval titles use it before the resource id.
- Sensitive elements carry none of the new words; gateways refuse an observation that does (as they already did for text).
- The phone reads every application window of the app in front (not system UI, not Latch), top window first, and marks an element of a lower window `covered` when its centre lies under a higher window. Taps on a covered element still run (the AI may know better); gateways tell the AI that the window on top receives them.
- `ui.observe.marks` (boolean): the phone draws each listed element's id on the screenshot in a small label at its top-left corner, in Latch's ember with a dark outline. Older phones ignore it and send an unmarked screenshot. Gateways ask for marks only with a screenshot.
- The phone builds the element list and screenshot from the same moment: it compares the screen's fingerprint before and after the screenshot and reads the list again if it changed.
- Gateways add one **reading note** under the list when it is incomplete: controls without a name, names guessed, a web page, a drawn area (video, camera, map, game), almost nothing to read, and how many elements are covered. The AI decides whether to look at a screenshot; nothing is turned off.
**Threat review:**
| Who | Can they… | Why not, or what limits it |
|---|---|---|
| A hostile app | steer the AI through state words, hints, action names, or a fake label | All new words are untrusted screen content, quoted and length-limited like text; the banner says so |
| | hide a consequential button behind a harmless guessed label | A guessed label only adds words for policy; the element's own words and resource id are still judged |
| | leak a password field's contents through `hint` or `state` | Sensitive elements carry no words; both gateways refuse an observation that breaks this |
| A tricked AI | press an app's own action ("Delete") without approval | Actions are listed, not invoked: invoking them is not part of this decision |
| | learn more of the screen than before | Only what Android already gives the service and the screenshot already shows |
**Not covered:** invoking the apps' own actions (needs a policy review of action names first), memory of learned names per app, and reading inside web pages beyond what Chrome exposes.
**Checks:** shared fixture `packages/schemas/v1/mcp/render-rich-*` (every new fact, covered menu, reading note) byte-identical in Rust and TS; protocol validation for sensitive words, action count, and percent on both gateways; a policy case for a consequential guessed label; fake-phone e2e on both gateways (state words, headings; marks asked only with a screenshot); Android JVM tests for labels, actions, covered windows, and marks.
**Reversal trigger:** guessed labels misleading the AI more often than they help in the R10 evaluation runs, or a common app whose state words or action names carry secrets.

## ADR-045 — Owner-authorized ADB shell through MCP
> **Superseded controls (2026-10-17):** [ADR-046](#adr-046--unrestricted-owner-authorized-adb-over-wireless-mcp) replaces this ADR's per-command approval, session expiry, scope/filter, timeout, output, and quota requirements. The text below records the earlier decision; it is not the current control policy for full-access ADB.

**Status:** accepted for wireless ADB architecture; operational restrictions superseded by ADR-046; **implementation:** TODO, roadmap R27. **Protocol:** unchanged by this documentation decision; version the addition when built.

**Owner clarification (2026-10-17):** MCP over Wi-Fi must provide the same owner-authorized Android shell capabilities as direct ADB on the target phone. The required setup, pairing, reconnect, and normal execution path is cable-free. USB is optional developer tooling, not a prerequisite, fallback requirement, or acceptance gate. This clarifies the earlier Wi-Fi/USB wording; it does not claim runtime support exists.

**Context:** the owner now requests Android ADB shell support in Latch, exposed to MCP clients over Wi-Fi wireless debugging with direct-ADB shell capability parity and no required wired connection. Earlier decisions prevented an implementation session from proceeding. This decision supersedes ADR-008's shell prohibition, the ADB rejection portions of ADR-041/042, and ADR-043's 2026-10-16 refusal of ADB-level access. It does not remove their existing file-transfer, TLS, or typed Android capabilities. The older Notion mirror still has the former prohibition; preserve this newer repo-authored decision when syncing.

**Decision:**
- Build a real optional ADB shell capability, provisionally `adb.shell`, behind a typed, versioned MCP/device contract. This is shell execution on the explicitly selected Android device, never arbitrary execution on the gateway host. iOS reports unsupported.
- Give MCP clients over Wi-Fi the same authorized command surface, execution identity, and shell results as direct ADB permits on that phone, with Latch's consent/lifecycle controls applied consistently. Do not deliver only discovery/status, accessibility actions, or a reduced public-API command list as a substitute. "Same access" means the device's normal authorized Android shell permissions, not root, exploits, or bypassing Android protections.
- Keep it off by default, separately granted to a device, MCP client, and expiring session. Existing app switches, Auto mode, file grants, and saved workflows do not grant it.
- The owner enables Developer options/debugging and authorizes the ADB peer using Android's consent/pairing flow. Wi-Fi uses authenticated wireless-debugging pairing without a USB bootstrap. An optional wired development path requires an authorized host with physical access. Do not automate consent, publish debugging ports, or silently fall back to legacy TCP debugging.
- Treat shell commands as critical and show the exact command and target for fresh owner approval. Design and test an approval channel the ADB agent cannot operate. Existing overlay protection cannot be assumed sufficient once shell input exists.
- Return bounded stdout/stderr, exit status, truncation, timeout/cancellation, and transport failures. Bind each request to the device/client/session and deadline, limit concurrency, and never retry an uncertain side effect automatically.
- Stop, pause, expiry, revoke, and connection loss end dispatch and cancel Latch-managed work. Document and test process cleanup; disconnected/detached processes and completed side effects must not be falsely reported as stopped or rolled back.
- ADB authority exceeds Android app permissions and Latch's app/file allowlists. This mode must explain that difference. A command blacklist is not a sandbox; ordinary UI redaction does not cover arbitrary command output. Credential extraction, OTP/MFA bypass, biometric automation, covert capture, and bypassing Android authorization remain excluded. Keep raw commands/output and ADB credentials out of persistent audit/telemetry by default.

**Execution boundary and feasibility:**
MCP transport, Latch's phone channel, and ADB are separate boundaries. ADR-042's Wi-Fi gateway does not create ADB privileges. Prefer the existing gateway/device structure; prove an owner-authorized executor before choosing an additional component. A serverless/cloud gateway cannot run USB ADB or reach an arbitrary home LAN directly. Both gateways must share the contract and authorization behavior, forwarding only to a verified reachable executor or returning an explicit unsupported/unavailable result. R27 requires a real cable-free execution path without a mandatory laptop daemon. Validate an owner-authorized on-phone wireless-debugging executor or another architecture that satisfies that requirement before claiming support. An optional owner-controlled wired development host does not satisfy the required product path. Record a concrete feasibility blocker if no compliant executor is possible; do not silently replace wireless parity with USB or simulated commands.

**Alternatives:** retaining only ADR-043's typed public APIs does not satisfy the owner's requested shell feature. Exposing an unrestricted host shell or silently inheriting existing app consent is rejected. Keep typed APIs as the simpler path when they already solve a task.

**Threat review and release gate:**
| Risk | Required control or evidence |
|---|---|
| Injected agent or stolen MCP key requests a shell | Separate grant; device/client/session binding; fresh owner approval; deny/expiry/revoke tests |
| ADB input approves its own command or changes its own grant | Prove the approval/grant boundary on a real device before release; do not rely only on serialized accessibility commands |
| Wrong phone, stolen ADB key, or exposed debugging service | Explicit target binding, owner pairing, protected keys, documented forget/revoke recovery; no public ADB endpoint |
| Shell crosses app/file boundaries or returns secrets | Explicit broader-access disclosure; no claim that allowlists or keyword filters sandbox it; sensitive output controls and no content logging |
| Timeout, disconnect, detached work, or replay | Bounded resources, managed-process cleanup tests, no automatic retry after uncertain execution, honest residual-state reporting |
| Gateway parity or old-client drift | Shared fixtures/policy cases, Rust and TS MCP tests, capability/version negotiation, structured unsupported results |

**Acceptance:** complete R27's tests and document measured limitations: clean setup, owner pairing, harmless shell execution via MCP over Wi-Fi, and reconnect with no USB cable used throughout; parity with direct ADB's authorized Android shell capabilities on the same device (compare the same harmless commands/results with a wired reference when one is available, without making USB a product prerequisite); disabled and ungranted refusal, owner denial, wrong-client/device refusal, expiry, output cap, timeout, disconnection, Stop/revoke, restart/reconnect without duplicate execution, and protection against self-approval. Keep the feature IN_REVIEW if hardware evidence is missing. Existing no-shell tests must become authorization/unsupported tests in the implementation, not simply be removed.

**Rollback:** turn the shell grant off, cancel managed work and pending approvals, disconnect the executor, and guide the owner to forget/revoke its ADB key in Android. Existing typed UI/file features continue. Completed system changes are not automatically undone.

**Reversal trigger:** the chosen execution path cannot enforce an independent owner-approval boundary or honestly stop managed work, leaks credentials/output, or requires exposing ADB publicly. Gate that backend and record the concrete limitation; the superseded blanket prohibition is not itself a blocker.

**Platform reference (checked 2026-10-17):** [Android Debug Bridge documentation](https://developer.android.com/tools/adb) describes shell access, USB authorization, wireless-debugging pairing, and forgetting paired hosts. It does not establish that Latch has implemented or verified any of these paths.

## ADR-046 — Unrestricted owner-authorized ADB over wireless MCP
**Status:** accepted for implementation (2026-10-17, explicit owner request); **implementation:** R27 IN_PROGRESS — protocol 1.17 (`adb.shell` capability/command, `AdbResult`), both gateways, the fake-device executor, the on-device ADB client (our own ADB protocol/key/handshake/`shell,v2`/executor), and the cable-free wireless-pairing stack (our own Ed25519/SPAKE2, AES-128-GCM pairing, self-signed X.509, TLS, mDNS, and pairing UI) are built and unit-tested; the live on-device interop and the cable-free real-phone proof remain (`*needs phone*`). **Supersedes:** ADR-045's additional Latch command restrictions and conflicting ordinary-tool policy for this mode. **Protocol:** no runtime change in this documentation commit.

**Context and owner decision:** the owner requires the same access as direct Android ADB, without extra Latch limits, over MCP/Wi-Fi rather than a cable. The earlier per-command approval, expiring session, deadline, scope, and output-cap requirements did not satisfy that request.

**Access model:**
- ADB mode starts off. The owner explicitly enables it, chooses the phone/client trust, and completes Android's debugging authorization/pairing. Authenticate endpoints, protect ADB keys, and bind each request to the intended phone. These establish who has access; subsequent commands do not require another owner approval.
- Once enabled, provide the same device-side command surface, effective ADB identity, supported operations, and complete results/errors as direct ADB on that device. Include arbitrary shell commands/scripts and supported device-side ADB operations such as file transfer, package management, diagnostics, and forwarding. This is access to the selected phone, not the gateway computer's host shell or host ADB-server administration (such as `adb kill-server`). Do not silently substitute the Android app UID or a reduced public-API command list.
- Add no command allowlist/denylist, app/folder scope, approval per command, mandatory grant/session expiry, policy timeout, command/input/output cap, rate/concurrency quota, or content-based command/result filter. Do not apply ordinary UI/file sensitive-field, consequential-action, app-consent, or Auto-mode rules to this bridge. The owner may voluntarily configure limits or a caller may request cancellation/deadlines; Latch must not impose them as compulsory defaults.
- Stream or resume large/long-running commands and their full output. Use backpressure and finite transport frames without turning them into an artificial command denial or silently truncated result. Document actual transport/platform capacity or failures honestly; finite hardware is not a promise of physically unlimited throughput.
- The owner's grant remains until the owner disables or revokes it. Authentication credentials may still expire, rotate, or refresh without forcing a new command approval or expiring the ADB grant. Owner Stop/revoke blocks further dispatch and closes managed channels. Cancellation/disconnect is best effort and does not undo completed effects or guarantee every detached process stops. Do not automatically replay uncertain side effects after reconnect.
- Keep authenticated wireless debugging, initial owner authorization, exact device binding, and protected credentials. Owner-enabled ADB is not permission to expose an unauthenticated gateway/ADB server or bypass Android pairing. Return complete unfiltered results only to the authorized client; keep command bodies/output out of persistent logs by default. Output is untrusted data, not instructions for executing on the gateway host.
- Android's actual ADB permissions and feature availability still apply, exactly as with direct ADB on that phone/build. Latch neither invents permissions the OS denies nor adds its own restrictions to OS-supported operations. This decision does not claim every retail phone supports root or every ADB feature.

**Wireless architecture:** preserve ADR-045's cable-free setup, pairing, operation, and reconnect requirement, with USB only optional developer tooling and no mandatory laptop daemon. MCP and Wi-Fi carry requests; they do not themselves create Android privileges. Prove an owner-authorized reachable executor and equivalent effective identity. Both Rust and TypeScript gateways must preserve the same access model. If a backend cannot supply it, report a concrete feasibility blocker rather than presenting a limited substitute as full ADB.

**Risk accepted:** any authenticated client to which the owner grants full ADB trust can perform the same broad and potentially destructive device actions as a direct ADB peer, without a second confirmation. App/folder isolation, sensitive-output filtering, and per-command approval are not promised in this mode. Keep ordinary UI/file mode available with its existing protections. Do not create separate product features for unauthorized access or defeat OS security controls.

**Verification and acceptance:** tests must prove initial enrollment and authentication, exact device targeting, arbitrary benign shell/scripts, representative supported ADB operations, complete large-output and long-running results, continued operation beyond ordinary session timers, no inherited per-command approvals/filters/quotas, owner Stop/revoke, OS refusal propagation, disconnect/reconnect without duplicate execution, and gateway parity. Record a cable-free real-device run before marking R27 DONE. Keep old shipping-protocol behavior documented until versioned implementation lands.

**Recovery:** disable the bridge and revoke/forget its Android ADB pairing, rotate affected credentials if needed, and report any outstanding execution honestly. Preserve existing ordinary UI/file functionality. This documentation-only decision does not ship the runtime.

**Decision precedence:** AGENTS.md and active handbook guidance route new sessions to R27 and this ADR. The ordinary capability rules elsewhere (including deadlines, app/folder scoping, required observations, approval overlays, redaction, rate limits, and fixed session duration) are not additional restrictions on owner-enabled full-access ADB. Older ADR text remains historical; do not restore those restrictions during a Notion sync.

**Reference:** [Android Debug Bridge](https://developer.android.com/tools/adb) documents the actual ADB command/permission boundary and wireless pairing; implementation must validate support on the tested device/build.

## ADR-047 — Any gesture, judged finger by finger; ADB beside screen commands
**Status:** accepted (2026-10-17, owner request); protocol 1.18, roadmap R28 IN_REVIEW · *needs phone*.

**Context:** the owner asked for every Android gesture, for ADB output that can be binary, and for long ADB commands not to block the other tools. An audit also found that a swipe that barely moves (for example from (990, 2300) to (1000, 2310)) is a tap to Android, yet policy only checked its start for a password field, so it could press Send or Pay without the approval a tap needs.

**Decision:**
- `input.gesture`: up to 10 fingers, each a path of up to 64 points with its own start and duration, ending within 15 s (inside an ordinary command's 20 s deadline; Android allows 60 s). Every point must be on screen; no finger may start on a sensitive element, in the status bar, on Latch's own windows, or touch the keyboard (text goes through `type_text`). Any finger whose path stays within 32 px of its start (Android's touch slop is ~8 dp) is judged exactly like a tap, or a long press when it lasts 400 ms or more, by all three checks (Rust policy, Vercel policy, the phone). The gesture needs the approval its most consequential finger needs. The same rule now applies to `input.swipe`.
- `adb.shell` gains `base64`: stdout is base64 of the exact bytes. The `adb` tool shows a whole PNG, JPEG, GIF, or WebP as an image; other binary stays base64 text.
- `adb.shell` runs beside screen commands: gateways skip the per-phone command lock for it, and the phone runs it outside its serialized worker on its own adbd connection. Screen commands stay one at a time. This removes a concurrency limit, which ADR-046 forbids anyway.

**Rejected:** letting gestures bypass tap judgement (it would undo the consequential-action rule for ordinary tools); a 60 s gesture (it would outlive the command deadline and look like a failure).

**Recovery:** a phone below 1.18 is refused `input.gesture` and `base64` by both gateways, with the usual "update the app" message; every other tool is unchanged.

## ADR-048 — A command survives a reconnect and runs at most once
**Status:** accepted (2026-10-17, owner request R29 "no repeated actions"); protocol 1.19, roadmap R29 (a).

**Context:** when the phone's network dropped after it ran an action but before its answer arrived, the phone kept its answer and posted it on the next connection (for 20 s), but both gateways discarded answers that arrived on a connection other than the one the command was sent on, and told the AI "the phone disconnected". The AI then repeated the action: a tap, a sent message, or a payment step done twice. A command whose first copy was lost on the way to the phone also failed instead of being delivered. Handbook ch. 04 requires that a reconnect does not duplicate a non-idempotent action, and ch. 08 forbids automatically replaying an uncertain side effect.

**Decision:**
- **Gateways (Rust and Vercel):** a command in flight belongs to the phone, not to one connection. When the phone reconnects, the command carries over; an answer counts on any of the phone's authenticated connections (command ids are random). A phone on 1.19 or later is sent each carried-over command again, with the **same id**. A phone that dropped off without a new hello gets a grace of 30 s (the Vercel gateway notices a quiet phone after 45 s and adds the same grace); then the call fails `device_unavailable` with "the action may already have happened, so observe before repeating it".
- **Phones (Android and the fake phone):** each id runs at most once. A repeated id that is still running is ignored (that run answers); one that already answered gets the same answer from memory; one whose answer is no longer kept is answered `cancelled` without running. Android keeps its last 64 answers (at most 8 MiB, since they can hold screenshots) in memory only, and appends the ids alone, random tokens with no personal data, to `files/session/recent-commands.txt` (newest 1024, excluded from backups), so a command that ran just before Android stopped the app is not run again by the restarted app.
- **Stop means stop:** when the phone says `bye` (owner Stop, unpair, session end), its commands in flight fail at once and are never sent into a later session. Revocation also fails them at once.
- **Older phones:** a phone below 1.19 does not remember ids, so it is never sent a command twice; its commands fail on disconnect exactly as before (with the clearer message).

**Why resending is safe:** the phone, not the gateway, decides whether to act, and it acts only on an id it has never seen. A command it never received still carries its `observation_id`, so if the screen changed meanwhile it is refused as stale rather than acted on blindly.

**Rejected:** retrying in the AI client (it cannot know whether the action happened); a gateway-side result cache (the gateway cannot know whether a lost command ran); persisting answers on the phone (they hold screen content, which never goes to disk).

**Recovery:** if the phone's journal file is lost, only the restart case loses protection; memory still protects every reconnect. Reverting the change restores the old fail-on-disconnect behavior; a 1.19 phone talking to an older gateway is simply never sent a command twice.

## ADR-049 — ADB jobs: start now, collect later
**Status:** accepted (2026-10-17, owner request R29 "long ADB jobs"); protocol 1.20, roadmap R29 (d).

**Context:** a full-access `adb.shell` command ends when its tool call ends: when the AI app stops waiting, or at the Vercel function's 300 s limit (R2). Builds, long `logcat` captures, big copies, and benchmarks take longer. The workaround, `nohup … &` to a file, works but leaves the AI to manage files, exit codes, and clean-up by hand, and leaves output behind on the phone.

**Decision:**
- `adb.shell {command_line, background: true}` starts the command on the phone and answers at once with a `job` id. `adb.shell {job, wait_ms?}` returns what the job wrote since the last collection, waiting up to `wait_ms` for it to finish; `finished` comes with the exit code once everything was read, after which the id is forgotten. `{job, cancel: true}` ends it. The `adb` MCP tool on both gateways takes `background`, `job`, `wait_ms`, and `cancel`, and says how to collect a started job.
- The job runs in the phone app's own scope on its own adbd connection, outside the command that started it, so it outlives that call, the link, and reconnects. Its output goes to two files in the app's private cache and is read from where the last collection stopped (at most 16 MiB per collection, ending on a whole UTF-8 character; the rest waits for the next). It is deleted once collected, on cancel, on owner Stop, when the owner turns full ADB off, and at app start.
- No Latch-imposed limit on how long a job runs, how many run, or how much they write (ADR-046): free disk space is the limit. The caller's own `timeout_ms` still applies to the job if it asked for one.
- `background` does not combine with `base64`: base64 of separately read pieces does not join into one valid encoding. Binary output goes to a file in the job and is read afterwards with `base64`.

**Privacy:** ADB output now waits on the phone's disk until it is collected. It stays in Latch's private, non-backed-up cache, is never logged, and is gone as soon as it is collected or the session is stopped. The Activity log records no command output, as before.

**Rejected:** jobs on the gateway (the Vercel gateway cannot keep a task alive between requests, and the Rust gateway would lose them on restart); keeping output in memory (an unbounded job would exhaust it, and a cap is forbidden by ADR-046).

**Recovery:** a phone below 1.20 is refused `background` and `job` by both gateways with the usual "update the app" message; ordinary `adb` calls are unchanged.

## ADR-050 — Full ADB on mobile data: an opt-in fixed port until restart
**Status:** accepted (2026-10-17, owner request R29 "ADB off Wi-Fi"); Android app, roadmap R29 (b) · *needs phone*.

**Context:** full-access ADB runs over Android's wireless debugging (ADR-046), which Android ties to Wi-Fi: off Wi-Fi, on mobile data, `adb` stops working. Android's own answer is `adb tcpip 5555`: adbd also accepts classic ADB on a TCP port until the phone restarts.

**Decision:** an owner switch, **Access → Full ADB access → Keep working on mobile data**, off by default. Switching it on asks adbd over Latch's authenticated wireless-debugging connection for the `tcpip:5555` service; Latch then reaches adbd on `127.0.0.1:5555`, on the phone itself, whenever the wireless-debugging endpoint does not answer and mDNS finds none. Switching it off asks for `usb:`, which closes the port at once; turning full ADB off does the same. The port closes by itself when the phone restarts, and the switch then has no effect until it is used again on Wi-Fi.

**Threat review:** while open, adbd accepts connections on port 5555 on every network the phone is on, not only from Latch. A peer still needs an RSA key adbd trusts: Latch's own, or one the owner allowed; an unknown key makes Android ask the owner, who can deny. On mobile networks the phone is usually not reachable from outside (carrier NAT); on shared or public Wi-Fi other devices can reach the port and try. The connection is classic ADB, so it is authenticated but not encrypted on the network; Latch's own use never leaves the phone (loopback). The risk is stated next to the switch, the default is off, the port does not outlive a restart, and every change is in the Activity log.

**Rejected:** keeping the port open across restarts (needs root); a VPN or tunnel (new infrastructure and permissions for one feature); opening the port without asking (the owner must choose the exposure).

**Recovery:** switch it off, or restart the phone. Revoking ADB keys under Developer options also locks out any other key at once.


## ADR-051 — Live mirror: the phone's screen in the owner's browser, through the self-hosted gateway
**Status:** accepted (2026-10-18, owner request R30 "see and control the phone live from any computer, with as little delay as possible, platform APIs only") · **Channel:** mirror v1 (`docs/protocol/mirror.md`); the device protocol is unchanged · **Builds on:** ADR-013 (MediaProjection kept for a video stream), ADR-037 (device keys), ADR-042 (the gateway on the owner's computer)

**Problem:** the owner wants to watch the phone live from a laptop, follow what the AI does, and take over with mouse and keyboard, from any browser on any desktop system, with no app to install and no third-party library on the phone.

**Research that changed the design (2026-10-17 draft, `docs/design/live-mirror.md`):** the draft's fast path, a TLS listener on the phone with a self-made certificate streaming straight to the browser, cannot work in a browser:
- browsers cannot pin a certificate, so the phone's own certificate gets a full-page warning, and an `https` page may not open `ws://` to a LAN address (mixed content; Chrome's Private Network Access blocks it as well);
- WebCodecs exists only in secure contexts, so a page served by the phone over plain `http` has no video decoder at all.
`http://127.0.0.1` and `https` with a real certificate are secure contexts. So the stream always goes through the self-hosted Rust gateway: run on the owner's own computer (ADR-042), the browser opens `http://127.0.0.1:8787/mirror` and the phone's stream crosses only the home Wi-Fi, which is the fast path; run in the cloud, the browser uses the gateway's `https` address. The Vercel gateway cannot hold a two-way stream (R2) and has no mirror routes; the phone says so.

**Decision:**
- **Phone:** **Mirror on computer** asks Android's screen-capture consent each time (`MediaProjection`, full screen only on Android 14+), runs a `mediaProjection` foreground service with a Stop action, and dials `GET /v1/mirror/phone` with its device token. Its first message is signed with its device key, bound to a random connection id the gateway chose (as every device-link message is, ADR-037). It encodes with the hardware H.264 encoder (`MediaCodec`, Baseline, low-latency keys where the device has them, frames straight from the encoder's input `Surface`) only while someone is watching, and sends one binary message per frame. Browsers without WebCodecs get JPEG stills a few times a second.
- **Joining:** the phone makes a 256-bit viewer key and the gateway a 6-digit code that works once for 10 minutes. The phone shows the address and the code; the owner types the code on the page, which gets the key in exchange and reconnects with it. A link with the key (`/mirror#k=…`) can be copied from the phone instead. Wrong codes count against every live code (5 kill it) and against the gateway (20 a minute pause code joins). The key ends with the mirror.
- **Gateway:** relays bytes. It never decodes, stores, or logs a frame. A browser that falls behind loses frames up to the next key frame, which the phone is asked for; video is never queued. A phone that drops off keeps its viewer for 15 s and resumes with the same key. Browser messages pass only as validated, rebuilt `touch`/`wheel`/`text`/`key`/`button`/`control`/`ping`/`sync`/`codec` messages; cross-site WebSocket upgrades are refused. Revoking the phone ends its mirror.
- **Input** is the owner's own, performed through the accessibility service: taps and live drags (`dispatchGesture` with `continueStroke`), scrolling, typing into the focused field, Back, Home, Recents. It is logged as the owner's. The first input while an AI session runs pauses the AI ("You're in control"); **Let AI continue** resumes it, and stale observations are already refused, so the AI looks again first.
- **AI layer:** the page shows the session state and the latest Activity lines (who, app, one-line summary; never screen or typed text) as the phone sends them.
- **No `screen.mirror` device capability** (the draft had one): mirroring is not a device-protocol command and no AI client can ask for it. The owner's tap on **Mirror on computer** and Android's consent each time are the switch, and the foreground notification is the indicator.

**Threat review:**
| Who | Can they… | Why not, or what limits it |
|---|---|---|
| An AI app (MCP client) | open the mirror, or see frames | No MCP tool or route reaches it; viewers join only with the phone's code or key |
| Someone who sees the code | watch or control the phone | The code works once and only while mirroring runs; the phone shows when a viewer is connected and Stop ends everything |
| Someone guessing codes | find a live one | 10⁶ codes, 5 wrong tries per mirror, 20 per minute per gateway |
| A copied link | keep watching later | The key dies when mirroring ends; a new mirror makes a new key |
| A page on another site | drive the owner's browser to connect | Cross-origin upgrades are refused; the key never leaves the page's session storage |
| A stolen device token | pose as the phone and show a fake screen to collect the owner's typing | The start must be signed by the device key in the phone's keystore |
| The mirror's input | change Latch's own switches (full ADB, Auto mode) | Input on Latch's own windows and the approval card is refused; those stay on the phone itself |
| Password fields | send passwords through the gateway | Typing into a password field from the mirror is refused; the page says to type it on the phone |
| The gateway operator | read the screen | The same trust as for observations today (ADR-012): self-host. Frames are relayed in memory only, never written or logged |
| Protected screens (`FLAG_SECURE`) | be captured | Android captures them as black; the page says the app protects the screen |

**Rejected:** the phone's own TLS listener (see research); WebRTC (needs a third-party stack on the phone, or our own ICE/DTLS/SRTP); an MCP tool (the mirror is for a person; the AI already has `observe`); Vercel (cannot hold the stream).

**Checks:** Rust: validation, key and frame unit tests; e2e on a real gateway: signed start required, code once and exchanged for the key, frames byte for byte, invalid input dropped, take-over from another window, phone reconnect within the grace, end, revocation, cross-site refusal, locked-down page, and a slow browser that skips to a key frame (never a delta after a gap). Node: frame parsing, SPS to codec string on a real x264 key frame, coordinate and wheel mapping, keys, paste. Android JVM tests for the message parser (shared fixtures), stroke planning, text editing, encoder sizing, and the bitrate governor. *Needs phone:* encode time and glass-to-glass delay on two phones, H.264 decode in Chrome/Edge/Safari, and how live drags feel.

**Recovery:** Stop on the phone, its notification, Android's own capture chip, or **End mirroring** on the page ends it at once; revoking the phone ends it at the gateway. Removing the routes disables the feature without touching the device protocol.

## ADR-052 — Two ways to connect, both kept; a Wi-Fi gateway found again at a new address
**Status:** accepted (2026-10-18, owner request R31 "give the user the option to connect through Wi-Fi or the hosted gateway … switch back and forth … it saves the data so the phone won't ask … the gateway creates a new URL each time") · **Builds on:** ADR-012 (phone dials out), ADR-029 (Vercel relay), ADR-037 (device keys), ADR-042 (the gateway on the owner's computer) · **Protocol:** unchanged

**Problem:** onboarding offered only "make a Vercel relay" or a generic "I have a code or a relay"; the Wi-Fi gateway was hidden behind the second. Changing from one to the other meant **Disconnect**, which deletes the device token and the owner key, so switching back needed the key again. And the Wi-Fi address carries the computer's DHCP address, which routers change, so the owner had to copy a new address and pair again.

**Decision:**
- **Choice in onboarding:** **Set up Latch** opens *How should your AI reach this phone?* with two cards: **Hosted relay** (the Vercel flow as before) and **Computer on Wi-Fi** (install command, `latch-start`, then the address with `#pin=` and the owner key; a pin is required on this path).
- **Both kept:** the phone keeps at most one connection of each kind (kind = has a pin → Wi-Fi, else hosted). The one not in use lives in its own slot: address, device id, pin in preferences; device token and owner key in their own Keystore-sealed `TokenVault`s; the per-pairing device-signing key (ADR-037) is not deleted. **Connect → How your AI reaches this phone** switches with one tap (the session stops first; approval grants of the session are cleared), or, when the other kind is not set up yet, keeps this one and opens onboarding at that kind. The first screen lists kept connections ("no key needed"). **Disconnect** forgets only the connection in use; **Forget** on a kept one deletes its keys and signing key. A new pairing of a kind replaces the kept one of that kind.
- **Same address across restarts:** the gateway's certificate (and so the pin) and keys were already kept (`LATCH_DATA_DIR/wifi`, `~/.latch/latch.env`). When a Wi-Fi gateway stops answering (second failed attempt, then every fifth), the phone tries the same port on the other addresses of its own /24, nearest to the old one first, 32 at a time, and accepts only a TLS handshake whose certificate key matches the pin; it then saves the new address and the link continues. Nothing is sent to any other host. `latch-autostart on|off|status` (installed with the gateway) keeps it running as a LaunchAgent or systemd user service, so it need not be started by hand.

**Threat review:**
| Who | Can they… | Why not, or what limits it |
|---|---|---|
| Another device on the Wi-Fi | pose as the gateway after the computer moved | Accepted only with the pinned key; its private key never leaves the owner's computer |
| A network observer | learn something from the search | It sees TLS handshakes to port 8788 on the local /24 and nothing else; no token or data is sent before the pin matches |
| Someone with the unlocked phone | use a kept connection | Same as the one in use: the tokens are Keystore-sealed and usable only by Latch; Forget deletes them |
| An AI app | switch the phone's connection | No tool or route; only the owner's taps in the app switch |

**Limits:** the search covers the phone's /24 only; a gateway on another network or a larger subnet needs the new address entered. Kept connections are this phone's only, not backed up.

**Rollback:** an older app reads only the connection in use and ignores the kept slot; nothing on the gateways changed.

## ADR-053 — Both connections at once during a session
**Status:** accepted (2026-10-18, owner request "let both stay connected … until they disconnect; the user uses either") · **Builds on:** ADR-016 (one command at a time), ADR-052 (two kept connections) · **Protocol:** unchanged

**Decision:** while a session runs, the phone dials out to the connection in use and, when the owner keeps one of the other kind and **Keep both connected** is on (Connect tab; on by default), to that one too. An AI app on either gateway can then use the phone, with the same switches, approvals, Stop, and session timer. The connection in use stays the one the Connect tab manages (AI apps, keys, other phones); the kept one only adds a way in: its drops and refusals are logged and never end the session. Live mirror uses the Wi-Fi gateway when one is kept, since only the self-hosted gateway relays video.

**Safety:** screen commands from both gateways share one lock, so the phone still runs one screen command at a time (ADR-016): an AI on one gateway cannot act while the owner is asked about the other's command. An approval is offered back only to the gateway whose command asked for it. Each link signs with its own pairing's device key (ADR-037) and keeps its own command memory (ADR-048). Stop, pause, and the timer end or hold both. Turning the switch off, or Forget on the kept connection, closes its link at once.

**Limits:** two AI apps may interleave screen commands; each still observes before acting, and stale observations are refused on the phone. A Wi-Fi gateway away from home retries quietly in the background.

## ADR-054 — Connect over Wi-Fi by scanning a QR code
**Status:** accepted (2026-10-18, owner request "can we scan a QR and it connects … adding keys from here to there … I want this very simple so even a non-tech user can use it"; the gateway terminal "should look professional") · **Builds on:** ADR-038 (owner key rotation), ADR-042 (the gateway on the owner's computer), ADR-052 (two kept connections) · **Protocol:** additive (`owner_key` in the pair answer; `owner` in the admin pairing request)

**Problem:** connecting a phone to the Wi-Fi gateway meant copying a 43-character pinned address and a 68-character owner key from the terminal to the phone. Owners pasted the AI app key instead, or the key of an older gateway still running, and got "the gateway did not accept the owner key", whose fix read like the Vercel flow.

**Decision:**
- **Setup codes:** with `LATCH_WIFI=1` the gateway makes a single-use **owner setup code** at start (8 letters and digits, the pairing alphabet, 15 minutes) and prints a QR code of `https://IP:PORT#pin=…&code=XXXXXXXX`, with the address and code under it for typing. `POST /v1/admin/pairings {"owner": true}` makes another; `latch-gateway pair` calls it on `127.0.0.1` with `LATCH_ADMIN_TOKEN` from the environment and prints a new QR code (`latch-start` does this when the gateway already runs in the background).
- **Pairing with one:** `/v1/pair` works as for any code and also returns `owner_key`: `LATCH_ADMIN_TOKEN`, or, when the owner rotated the key (ADR-038; only its hash is stored), a newly issued key that replaces the rotated one. The phone stores it as its owner key, so the Connect tab (AI apps, sign-in approvals, other phones) works as after the owner-key path. The answer is `Cache-Control: no-store`.
- **The app:** **Computer on Wi-Fi → Scan QR code** opens a scanner (zxing-android-embedded 4.3.0 at first; CameraX and zxing-cpp since the addendum below; it asks for the camera only then) and pairs at once; a QR code without a pin and code is refused. **Can't scan? Enter it by hand** takes the address and the setup code; an owner key in that field still works.
- **The terminal:** one summary at start (the phone's QR code, AI app URL, mirror, console, data folder), then compact log lines with local time and no module paths. rustls's own log is off by default (the phone's pin-checked address search makes many refused handshakes). The summary never shows the owner key or the MCP token.

**Threat review:**
| Who | Can they… | Why not, or what limits it |
|---|---|---|
| Someone who sees the terminal or the gateway log | become the owner | Only with the code, within 15 minutes, before the owner uses it; codes are single use. The same terminal already showed the owner key before this change |
| Another device on the Wi-Fi | guess a code | 31⁸ codes, at most 10 outstanding, 20 wrong guesses a minute lock pairing for a minute (as for every code) |
| A QR code from someone else | pair the phone with their gateway | The owner chose to scan in Wi-Fi setup; the address and pin come from the code, as they would when typed. The phone still asks for each permission and starts no session by itself |
| An AI app | get a setup code | Needs the owner token; MCP tokens are refused on `/v1/admin` |

**Limits:** the QR code needs a terminal with Unicode block characters (it is drawn black on white, so dark and light themes both scan; `NO_COLOR` draws it for dark themes). A gateway without a private address on the Wi-Fi prints no code until `latch-gateway pair` finds one.

**Rollback:** the hosted relay and the owner-key path are unchanged; an older app ignores `owner_key` and still accepts an owner key typed in the old screen. An older gateway has no setup codes: the app's typed owner-key path still works with it.

**Addendum 2026-10-21: the scanner reads with zxing-cpp through CameraX.** Owner report: the scanner still did not read the terminal's code after R51. The terminal drawing was checked first: the gateway's exact output, drawn with the cell shapes of common terminals (half blocks at 38–50 % of a cell, 6–9 px wide), decodes with ZXing 3.4.1. The failure was in the camera path: ZXing's Java reader (through zxing-android-embedded's Camera1 preview) loses a terminal-sized code once frames carry sensor noise or the code is a few pixels a module (read 4/40 simulated frames at 4 px a module, 2/20 at noise σ 8; zxing-cpp 38/40 and 20/20 on the same frames). Decision: CameraX 1.6.2 (`camera-camera2`, `-lifecycle`, `-view`; Apache-2.0; Google-maintained) for full-HD analysis frames, continuous focus, metering-point tap focus, zoom, torch and exposure; zxing-cpp 3.1.1 (`io.github.zxing-cpp:android`, Apache-2.0, maintained, 16 KB-aligned native libraries, about 0.7 MB compressed per ABI) reads them, tries inverted codes, and reports codes it found but could not read, whose size drives an auto-zoom (to about 45 % of the frame, at most 3×, never within 4 s of the owner's own zoom). ZXing core 3.5.3 stays for phones where the native library does not load and for photos zxing-cpp misses. zxing-android-embedded is removed. Nothing the camera sees is stored or logged; the camera is bound only while the scanner is open. Rollback: revert to zxing-android-embedded 4.3.0; the setup link and pairing are unchanged.

## ADR-055 — The phone's sound in the live mirror
**Status:** accepted (2026-10-18, owner request "video and music sound from the phone … asap") · **Builds on:** ADR-051 (live mirror) · **Protocol:** additive mirror frame kind 3

**Decision:** while someone watches, the phone captures what apps play (`AudioPlaybackCaptureConfiguration` with usages media, game, unknown) under the same screen-share consent, and sends 20 ms frames of raw 48 kHz stereo 16-bit PCM as mirror frame kind 3. Raw PCM needs no encoder or decoder, so it adds no delay; at about 1.5 Mbit/s it suits the home Wi-Fi the self-hosted gateway serves. The gateway relays sound frames without key-frame logic and drops one when the viewer is behind. The page plays them with Web Audio, keeping about 40 ms queued and jumping forward when more than 150 ms piles up; a speaker button mutes. Starting the mirror asks once for audio recording (`RECORD_AUDIO`); without it the picture works silently.

**Sound on the computer only:** while the page plays the sound (its speaker button on and the browser's audio running), it sends `{"t":"sound","on":true}` and the phone sets its media volume to zero, saving the old value; it restores it when the page sends `on:false`, the viewer leaves, or mirroring ends (unless the owner changed it on the phone). A volume index is used, not a stream mute, since a mute silences every copy. If the copy falls silent within about a second of going quiet while sound was playing, that phone applies the volume to the copy too: the volume comes back and the page is told.

**Limits:** Android lets no ordinary app capture calls or a call's microphone, and apps that opt out of capture stay silent. No sound from the computer reaches the phone.

**Threat review:** the sound goes only to the viewer who entered the mirror's code or key, like the picture; nothing is stored or logged.

## ADR-056 — Screen off while the phone keeps running; mirror controls for AI apps
**Status:** accepted (2026-10-19, owner request "screen off while mirroring keeps going … volume control must be exposed through MCP") · **Builds on:** ADR-046 (Full ADB), ADR-051 (live mirror), ADR-055 (sound) · **Protocol:** unchanged (gateway-side; tool schema additive)

**Decision:**
- **Screen light:** `phone_control` gains `screen_light` (`on`). The gateway runs, through the phone's Full ADB shell, `app_process` with Latch's own APK as class path and the `ScreenLight` helper, which sets the built-in display's power mode off or normal (`SurfaceControl.setDisplayPowerMode`; the token from `SurfaceControl`, or from `DisplayControl` in `services.jar` on Android 14+, as scrcpy does). The panel goes dark while the phone, the mirror, and AI actions keep working; the power button or `on: true` lights it. No file is copied to the phone. The mirror page has a button for it, which the gateway handles itself (never sent to the phone's mirror link); both gateways send the identical command (tested on both).
- **Mirror volume:** `phone_control` gains `mirror_volume` (`percent`): the gateway sends it to the page watching the mirror, which sets its volume slider. The hosted relay has no mirror and says so.

**Limits:** needs Full ADB on (the shell user may set the display power mode; apps may not). Some phones' power policy relights the screen on a touch or notification. A hidden API: a future Android may move it, and the helper then reports the error.

**Rollback:** older phones lack the helper and the command fails with its message; nothing else changes.

## ADR-057 — Mirror like a second screen: real input through Full ADB, clipboard, files, screenshots, status
**Status:** accepted (2026-10-19, owner request "notifications visible … copy and paste … drag a file … screenshot and screen recording … phone status … keyboard shortcuts … smooth as an external monitor") · **Builds on:** ADR-046 (Full ADB), ADR-051 (live mirror), ADR-055, ADR-056 · **Protocol:** device protocol unchanged; mirror channel additive ([docs/protocol/mirror.md](../protocol/mirror.md)); `phone_control` gains `mirror_notifications`

**Decision:**
- **Real input when Full ADB is on.** The phone starts `ShellInput` from its own APK through its own ADB shell (as ADR-056's `ScreenLight`). It injects touch, wheel, and key events with Android's input manager, as the `input` command does, so a drag follows the pointer live, a wheel scrolls natively, and typing uses the app's own cursor, Backspace, and keyboard shortcuts. It listens on the phone's loopback only and takes one connection that proves a 256-bit token known only to the app; it ends with that connection. Emoji and other text outside printable ASCII still go in through accessibility. Without Full ADB nothing changes: accessibility gestures and setting the field's text, as before. Latch's own windows and password fields stay refused on both paths.
- **Hint text is not text.** A field that reports its hint as its text (WhatsApp's "Message") is read as empty: by `isShowingHintText`, by matching the hint, by asking for the cursor at the end of that text (a real text takes it; an empty field does not), and by remembering what an emptied field reports. This fixes "Message" typed in front of messages and Backspace eating it.
- **Clipboard both ways, only on the owner's keys.** Ctrl/Cmd+V on the page puts the computer's text on the phone's clipboard and pastes it into the field in focus (a picture or file pasted is sent as a file). Ctrl/Cmd+C or X copies what is selected on the phone: with Full ADB, the app's own copy and then the clipboard as the shell reads it; without, the selection of the field in focus. The phone never reads the clipboard otherwise, and clipboard text is never logged.
- **Files, screenshots, recordings.** A file dropped, picked, or pasted on the page goes to `POST /v1/mirror/files` with the viewer key, is stored encrypted as an upload link, and the phone is told over its mirror link to fetch it into Downloads, with its SHA-256 checked; no AI session is needed. Screenshots are taken by the accessibility service at full resolution as PNG and sent as a frame of kind 4 that the gateway never drops; the page saves them. Recording is the page's own (`MediaRecorder` of the picture and the sound), saved on the computer.
- **Notifications in the mirror.** Android 15 hides notifications and protected apps from screen sharing. A page button (and `phone_control` `mirror_notifications`) sets Android's own developer option `disable_screen_share_protections_for_apps_and_notifications` through Full ADB, until the owner sets it back. Android applies it when sharing starts.
- **Status and shortcuts.** The phone sends battery, charging, network, and signal bars every few seconds while watched. Alt (Option) shortcuts run the page (Back, Home, Recents, notifications, screen off, full screen, rotate, sound, volume, screenshot, record, send files, pop-out, help); the editing keys work as on the computer. A pop-out window (Document Picture-in-Picture, Chrome and Edge) keeps the phone on top of other windows.
- **Lower latency.** The mirror socket has no Nagle delay and a 128 KiB send buffer; the phone drops delta frames after ~100 ms of queue and the gateway holds 3 frames per viewer; the bitrate starts at 8 and reaches 12 Mbit/s on a clean network.

**Not built:** mirroring one app in its own window while the phone shows another needs a second virtual display created by a shell process and a second video stream (what scrcpy's `--new-display` does); hearing calls needs the shell's voice-call audio source, which works only on some phones and only for listening. Both are possible later through the same helper; neither is pretended now.

**Limits:** the input helper and notifications switch need Full ADB. Injected text covers printable ASCII; other characters fall back to accessibility. Android 12+ shows its "pasted from your clipboard" note when an app reads text Latch put there. A future Android may move the hidden input or clipboard APIs; the helper then fails with its message and the mirror falls back.

**Rollback:** without Full ADB, or with an older app or gateway, the mirror works as in ADR-051; the new page buttons answer with a notice.

## ADR-058 — Reconnect without a new code: the mirror remembers the computer; Disconnect keeps the keys
**Status:** accepted (2026-10-20, owner request "whenever I've disconnected through phone for the screen mirroring and gateway it does not let me connect … make it easy as fuck to connect the devices … more simple") · **Builds on:** ADR-051 (live mirror), ADR-052 (kept connections), ADR-054 (QR setup) · **Supersedes:** ADR-051's "the key dies when mirroring ends" · **Protocol:** device protocol unchanged; mirror channel additive (close code `4404`)

**Problem:** every Stop on the phone made a new viewer key, so the mirror page that was open on the computer closed for good and the owner had to read and type a fresh 6-digit code for every restart. **Disconnect** deleted the phone's keys, so getting back on the Wi-Fi gateway needed a new QR code from the computer's terminal (the first one works once). A Wi-Fi drop left the link in a backoff of up to 30 s, and a mirror running without an AI session never looked for a computer that changed its Wi-Fi address.

**Decision:**
- **The phone keeps its mirror key** per pairing, sealed in the Keystore like the device token. Starting mirroring again (Android's capture consent is still asked every time) uses the same key, so a page that watched before joins without a code. A new key is made when the pairing changes, after 30 days unused, on **Forget computers** (Home's mirror card, shown once a computer watched), on the emergency **Stop**, on **Disconnect → Forget keys**, and on revocation.
- **The page waits for the phone.** A well-formed key with no live mirror behind it is closed with the new code `4404` (a malformed one still with `4401`). On `4404` or `4410` with a key, the page shows *Waiting for the phone* and asks again every 2 s for a minute, then every 5 s, for 30 minutes, at once when the tab comes back to the front, and joins the moment the phone mirrors; **Enter a code instead** leaves it. The page keeps the key in `localStorage` (falling back to session storage), so a reopened tab comes back too.
- **Disconnect keeps the keys by default.** Settings → **Disconnect from this relay** now offers **Disconnect** (the connection moves to the kept slot of ADR-052; the first screen shows *Reconnect: your …* — one tap, no key or QR code) and **Forget keys** (as before).
- **Reconnect when the network does.** On Android's "default network available", the gateway link and the mirror retry at once instead of waiting out their backoff. The mirror asks for the Wi-Fi gateway at a new address (ADR-052's pinned search) after two failed attempts and every fifth, also without an AI session, and follows the session's search when it finds one.

**Threat review:**
| Who | Can they… | Why not, or what limits it |
|---|---|---|
| Someone who once had the link or the page open | watch a later mirror | Only while the owner mirrors again, each time after Android's consent, and only until the owner presses Forget computers or the emergency Stop, disconnects with Forget keys, or 30 days pass unused. The phone's card says so next to **Copy link** |
| Someone guessing keys | find a live mirror | 256-bit keys; `4404` says only that no mirror runs for that key, which is true of every guess |
| Someone at the owner's computer | reuse the key from the browser | The same computer and browser profile the owner watches from; Forget computers on the phone ends it |
| A page on another site | join | Unchanged: cross-origin upgrades are refused |

**Checks:** Rust e2e: a made-up key closes with `4404`, a malformed one with `4401`, the phone's `end` closes the viewer with `4410`, and the same key joins again after the phone starts with it. Android: the scanner's still-picture decoder and focus arithmetic (JVM). *Needs phone:* stop and start the mirror with the page open; Disconnect → Reconnect over Wi-Fi; Wi-Fi off and on during a session and a mirror.

**Rollback:** an older page treats `4404` as a network drop and keeps retrying with its key, which joins once the phone mirrors; an older app makes a new key each time, as before.

## ADR-059 — A faster, lighter mirror stream (R35–R42, R44, R46–R50)
**Status:** accepted (2026-10-20, owner request 2026-10-19 "make our stream faster … optimize the app", and 2026-10-20 "continue with the rest of the backlog items") · **Builds on:** ADR-051 (live mirror), ADR-055 (sound), ADR-057 · **Protocol:** device protocol unchanged; mirror channel additive ([docs/protocol/mirror.md](../protocol/mirror.md))

**Decision:**
- **Measure (R35).** Alt+D on the page shows each second: stream size, fps, bitrate and the encoder's target; phone encode time (capture timestamp to encoder output); phone and gateway drops; capture→page on a clock synced by the fastest ping (`pong.phone_us`); page decode and draw; skipped deltas; glass-to-glass. `{"t":"stats","on"}` from the page, `stats` once a second from the phone while on; the gateway adds `gateway_drops`.
- **Off the page's main thread (R36, R47).** A Worker decodes and draws on the canvas it was handed; an AudioWorklet plays sound from a ring buffer. Frame buffers are moved, not copied. Browsers without OffscreenCanvas or AudioWorklet keep the old paths.
- **Recover without spikes (R37).** Where the encoder supports intra refresh, a phone-side drop heals within half a second without a key frame; a decoder error on the page asks for a key frame and falls back to stills only after three in ten seconds.
- **Follow the network (R38).** At the bitrate floor with frames still dropping, the encoder restarts at 1280 then 960 on the long edge and 30 fps, and steps back up after 15 clean seconds; `{"t":"quality"}` tells the page ("Saving bandwidth"), kept for late viewers.
- **Opus sound (R39).** When the page's WebCodecs decodes Opus it sends `{"t":"audio_codec","codec":"opus"}`; the phone encodes with Android's Opus encoder (API 29+, 96 kbit/s) and sends frame kind 5 on the sound queue; a failed encoder falls back to PCM, a failed decoder asks for PCM. Sound goes out only while the page plays it (R45).
- **H.265 where both ends can (R40).** A browser whose worker decodes `hev1` offers `h265` first; the phone uses H.265 only from a hardware encoder (Main profile, 60 % of H.264's bitrate for the same picture) and sends frame kind 6, else H.264 (kind 1). The page decodes by the frame's kind, takes the codec string from the SPS, and asks for H.264 if H.265 fails to decode. AV1 is not built: hardware AV1 encoders are rare on phones (the owner's test phone has none), so it could not be checked; it would follow the same pattern (a frame kind, an offer, a fallback).
- **Pinch from the computer (R42).** Ctrl+wheel and Safari's gesture events become `pinch` start/move/end messages (validated like other input); Full ADB's input helper injects two live pointers, accessibility plays one two-finger gesture at the end.
- **Copies follow (R44).** A page switch, off by default and remembered on that computer, sends `clipboard_follow`; with Full ADB the input helper checks the clip's description every 400 ms (not a read), reads the text once per new copy, skips clips Android marks sensitive (password managers), and the phone sends it to the viewer as a `clipboard` message. Nothing is logged; it ends when the viewer leaves. Threat note: only the owner's own switch on a page that already sees the screen turns it on, so it adds no reader the mirror does not have; Android 12+ still shows its "pasted" note once per copy.
- **Not built: WebTransport (R41).** It needs a QUIC client on the phone (Cronet) and an HTTP/3 server in the gateway with short-lived certificates; both are new dependencies that wait for the owner's decision and for R35 numbers showing loss on real Wi-Fi.
- **Lighter both ends (R46, R48–R50).** Stills on the image callback, one copy per frame, 500 ms repeat on a still screen; cheaper `wait_for`, settle, and screenshots; O(1) activity log; idle accessibility listening; gateway hot paths without copies or the global map.

**Threat review:** nothing new leaves the phone beyond encoder counters (frame counts, sizes, times) and the same sound in another encoding; every new message is validated and rebuilt by the gateway and covered by the shared viewer fixtures. The two new page scripts are served by the gateway under the mirror page's CSP (`script-src 'self'`).

**Checks:** Rust unit and e2e (stats both ways with gateway drops, quality kept for a late viewer, Opus frames valid, a replaced connection's frames ignored on the frame path); Android JVM (StreamStats, QualityLadder, MirrorAudio gating, ActivityLog); node (clock sync, overlay text, audio ring, worker); headless Chromium against a real gateway and a fake phone (overlay numbers, worker drawing, worklet and Opus playback at the expected level). *Needs phone:* every number in the R35 overlay on two phones, intra-refresh support, the quality steps on a congested Wi-Fi, and Opus on Android 10+.

**Rollback:** each part falls back on its own (no stats requested, no Worker or AudioWorklet, no intra refresh, Opus refused); an older page or phone ignores the new messages.


## ADR-060 — Find my computer: connect over Wi-Fi with no QR code, by comparing a number
**Status:** accepted (2026-10-20, owner: "can't we make this more easy … so even a child can link it"; on the proposal "the phone finds the computer on its own and both screens show the same short number to confirm": "yes go ahead and build this") · **Builds on:** ADR-042 (gateway on the owner's computer), ADR-052 (pin-checked address search), ADR-054 (owner setup codes) · **Protocol:** additive gateway routes (`/v1/nearby…`, `/v1/admin/nearby…`); the device protocol is unchanged

**Problem:** the QR code carries the gateway's address and certificate pin, which is what makes the phone's first connection safe. Without it the phone does not know which computer on the Wi-Fi is the owner's, nor its key, and anyone on the same Wi-Fi could pose as the gateway (or ask the gateway for an owner code).

**Decision:**
- **Finding it.** In the app, **Find my computer** tries port 8788 (the Wi-Fi listener's default) on every address of the phone's /24, as the reconnect search does (ADR-052), and keeps the hosts that complete a TLS handshake and answer `GET /v1/nearby` with `{"latch":"nearby"}` and the computer's name. The certificate's pin is recorded at that handshake, and every later request to that host goes through a client pinned to it alone, so the host cannot change keys during linking and the phone's other pins are untouched. No new listener or broadcast on either side.
- **The number.** A commitment-then-reveal exchange, as in Bluetooth numeric comparison, so a device in the middle cannot pick values that make the two numbers agree:
  1. phone → `POST /v1/nearby {"device": name}`; the gateway draws a 32-byte `Ng` and answers `{"id", "commit": SHA-256("latch-nearby-commit-v1" ‖ Ng)}`.
  2. phone draws `Np` and sends it (`POST /v1/nearby/{id}/nonce`); only then does the gateway reveal `Ng`. The phone checks it against the commitment.
  3. both compute `SHA-256("latch-nearby-sas-v1" ‖ pin ‖ Np ‖ Ng)`, take its first 4 bytes as a big-endian number mod 1 000 000, and show it as `123 456`. The phone uses the pin it saw, the gateway its own.
  A device in the middle has to commit to its own nonce before it sees the other side's, so it matches the numbers by chance only once in a million tries, and each try needs the owner to approve.
- **Approving.** The owner approves **on the computer only**, where the owner key lives: the request and its number appear in the `latch-start` terminal (when it is a terminal) and in `latch-gateway pair` (for a gateway in the background). The owner types `y` only when the phone shows the same number; anything else refuses. On approval the gateway makes an owner setup code (ADR-054) and returns it once to the waiting phone (`GET /v1/nearby/{id}`, long poll), over the pinned connection. The phone then pairs with `/v1/pair` exactly as after a scan. The phone can also cancel.
- **Limits:** at most 3 requests waiting, 10 new requests a minute, each request lives 2 minutes and is answered once, a device name of at most 40 printable characters, a number shown only after the nonces are exchanged. `LATCH_NEARBY=0` turns the routes off (they answer 404). The admin routes need `LATCH_ADMIN_TOKEN`, so MCP tokens and AI apps cannot approve.

**Threat review:**
| Who | Can they… | Why not, or what limits it |
|---|---|---|
| Another device on the Wi-Fi posing as the gateway (in the middle) | make the phone pair with it | The phone pins the key it saw; the owner approves on the real computer, where the number differs unless the attacker wins a one-in-a-million chance (commitment before reveal), and a mismatch is refused. With a match by chance, the attacker still gets only an owner code for a gateway the owner did not mean, through the phone, which asks for every permission and starts no session by itself |
| Another device on the Wi-Fi asking for an owner code | become the owner | Only if the owner types `y` for its request. The terminal shows the device name and the number, and says to approve only when the phone in hand shows the same number. Requests are limited (3 waiting, 10 a minute, 2 minutes each) |
| Someone flooding requests | hide the owner's request | The terminal shows each request with its number; the limits keep the list short; the owner can refuse and try again |
| An AI app | approve a request | Needs the owner token; MCP tokens are refused on `/v1/admin` |
| A passive listener | learn the setup code | It travels inside TLS to the pinned key and is single use for 15 minutes |
| A device answering at the paired gateway's address while the phone searches (ARP spoofing) | become trusted for the phone's own gateway connection | Found 2026-10-21 (the search wrote each host's pin into the phone's shared pins, replacing the paired gateway's). Since then the search and the linking use a client pinned to the found key alone (`GatewayPins.pinnedTo`); the shared pins change only when the owner pairs. Regression test `NearbyLinkTest.findingComputersLeavesTheSharedPinsAlone` |

**Not chosen:** mDNS/UDP announcements (a new gateway dependency and a multicast lock on the phone; the /24 search finds a gateway in a few seconds); approving on the phone only (the phone cannot know whether it talks to the real computer); a number from the pin alone (a 6-digit match against a chosen key takes about a million key generations, which is cheap).

**Limits:** the computer must listen on port 8788 and be on the phone's /24 (most home networks); a gateway on another port still links by QR code or by hand. Approving needs a terminal: `latch-start` in a terminal, or `latch-gateway pair` for a background gateway.

**Rollback:** turn it off with `LATCH_NEARBY=0`; the QR code and typed paths (ADR-054) are unchanged, and an older gateway answers 404, which the app reports as "no computer found".

## ADR-061 — The mirror's video and sound over encrypted UDP on the Wi-Fi
**Status:** accepted (2026-10-21, owner: "yes go ahead with WebTransport" and, on the proposal, "go ahead with all of it for mirror improvement build all of them") · **Builds on:** ADR-051 (live mirror), ADR-042 (gateway on the owner's computer), R41 · **Protocol:** mirror channel additive (`udp` text messages, UDP packets on the Wi-Fi port); device protocol unchanged

**Problem:** the mirror's WebSocket runs on TCP, which delivers in order. On Wi-Fi one lost packet holds every later frame until TCP sends it again; a loss at the end of a burst waits for TCP's retransmission timer, 200 ms or more on Linux and Android. That is the freeze-then-jump the owner sees on a good network. R41 proposed WebTransport. Android has no QUIC of its own, and Cronet (the only practical client) checks certificates with the platform's trust anchors, so it would not accept the Wi-Fi gateway's self-made, pinned certificate (ADR-042) without weakening that pin. Gateway to browser runs on the same computer and loses nothing, so WebTransport there would gain nothing.

**Decision:** what game-streaming tools (Moonlight, Parsec) do: UDP with the gateway asking for lost pieces again, no new dependency on either side.
- **Setup:** after `ready`, a gateway with a UDP socket (one beside the Wi-Fi listener, same port) sends `{"t":"udp","port":n}`. Only then does the phone send `{"t":"udp","id":"<16 hex>","key":"<64 hex>"}` on its authenticated, signed, pinned WebSocket: a random session id and a random AES-256 key, new for every mirror connection. The phone says hello over UDP (every 250 ms) until the gateway acknowledges, then sends video (H.264, H.265) and sound (PCM, Opus) frames over UDP. Stills and screenshots stay on the WebSocket. No word from the gateway for 3 s (a firewall, a restart) sends everything back to the WebSocket at once.
- **Packets:** `version ‖ session id ‖ counter ‖ AES-256-GCM(plaintext) ‖ tag`, the first 17 bytes authenticated as associated data, nonce `direction ‖ 000 ‖ counter`; counters never repeat under one key in one direction, and a 64-packet replay window drops repeats. A frame is cut into pieces of at most 1200 bytes (`T_FRAGMENT`: frame number, index, count).
- **Loss:** the gateway puts frames together and hands them to the same path as WebSocket frames, in order. A missing piece is asked for (`T_NACK`) as soon as a later frame shows it is missing, every 8 ms up to 4 times; the phone keeps the last 240 frames (at most 6 MiB) to send again. A frame that has not come 50 ms after a later one is complete is skipped, the viewer waits for a key frame, and the phone is asked for one. Every 250 ms the gateway reports packets received, lost, and frames skipped (`T_REPORT`); 2 % loss or a skipped frame backs the bitrate off as dropped frames do on the WebSocket.
- Both sides mark the socket DSCP AF41 (R54).

**Threat review:**
| Who | Can they… | Why not, or what limits it |
|---|---|---|
| Another device on the Wi-Fi | watch the mirror | Every packet is AES-256-GCM sealed under a key that travels only inside the phone's TLS connection to the pinned gateway |
| Another device on the Wi-Fi | inject frames or requests | A packet that does not open under the session's key is dropped before anything is read; replays are dropped by the counter window |
| Another device on the Wi-Fi | make the gateway send traffic elsewhere (reflection) | The gateway answers only the address of the session's latest packet that opened; unauthenticated packets get no answer; answers are smaller than what triggers them |
| A phone (or its key holder) | exhaust the gateway's memory | At most 256 frames and 24 MiB are being put together per session, 64 sessions; a frame far ahead resets the session's state; fragment counts and sizes are checked |
| Anyone | learn the key from logs | The offer is neither remembered nor passed to the viewer, and nothing about UDP is logged with keys or contents |

**Limits:** needs the gateway's Wi-Fi listener (UDP is bound beside it); a firewall that lets TCP 8788 in but not UDP leaves the mirror on the WebSocket (`ufw allow 8788/udp`). Input from the computer still goes over the WebSocket (small messages, rarely the ones lost). The hosted relay has no mirror.

**Rollback:** both directions mix safely. An older app ignores the gateway's `udp` message (unknown kinds are dropped) and stays on the WebSocket; a newer app offers its key only after that message, so an older gateway, which closes on phone messages it does not know, never receives one. To turn UDP off, block the UDP port: the phone falls back within 3 s.

## ADR-062 — A calmer, clearer look: one meaning per colour, light and dark, the safest answer first
**Status:** accepted (2026-10-21, owner: "go ahead and fix it also make it feel premium and easy to use even for the child") · **Amends:** ADR-025 (look), ADR-020 (bento tiles), the 2026-10 "terminal" theme (dark only) · **Protocol:** unchanged

**Problem:** a UI/UX audit of every screen (published to the owner as the *Latch Design Audit*) found:
- The brand ember (#FF6A3D) and the stop red (#FF5A5A) are 14° apart. Ember meant brand, main button, selected, running, and attention at once. The running and stopped hero cards differed by 1.02:1 in brightness, so people with red–green colour blindness could only tell them apart by words.
- `ListRow` cut subtitles at four lines, which likely hid the end of the Privacy note and the ML Kit disclosure in Settings.
- The approval card showed up to four answers of similar weight, and for an app request the filled main button was "Allow always", the widest grant. That contradicts "grant the smallest useful scope". Every approval looked the same, and people stop seeing a warning that always looks the same (Anderson, Vance et al.: fMRI and field studies 2015–2018).
- Home stacked up to six banners above its content. The first screen offered six ways in.
- Labels were 10.5 sp uppercase monospace, there were three type families, several touch targets were under 48 dp, the four pastel group tints were 1.03–1.06:1 against the surface (invisible), and the app was dark only, although dark text on light reads better for most people (Piepenbrock et al. 2013).

**Decision:**
- **Colour:** each colour has one job. The main button is neutral `ink` (cream at night, near-black by day). Ember is the brand and "selected" only. A running session is cyan (`live`). Red (`danger`, one `StopRed` everywhere, the floating pill included) means Stop and nothing else. The hero card's edge carries its state too (cyan, amber, red).
- **Themes:** the app follows the phone's light or dark setting. Night keeps the website's look; Day is dark ink on white. The hero card stays a dark panel in both, so its white text keeps its contrast. The camera screen is always dark.
- **Type:** Geist for every word; Geist Mono only for codes, keys, addresses, and numbers. Space Grotesk is removed (three font files fewer). Labels are 12 sp or larger, in sentence case.
- **Approvals:** two answers carry the weight, *Don't allow* and the smallest yes (*Allow once*, or *This session* for an app). Wider answers (*This session*, *Always in …*, *Always allow this app*) wait behind **More choices**. High-risk actions get an amber card with a 2 dp amber edge and a warning glyph, in the app and over other apps. Choices and their protocol words are unchanged.
- **Home:** one "needs you" slot, most urgent first (screen access, sign-in, update, outdated relay, slow relay), with "N more" for the rest. The hero leads with **Start · 30 minutes** (cream), then the length choice (48 dp). While a session runs, **Stop everything** fills the card's width, with Pause and Add time below. The tiles say what the AI may do and what it last did, in words, not counts. When a session ends, a card says how long it ran, how many actions in how many apps, the questions answered, and the steps refused.
- **Welcome:** the three promises come before any button. There is one main button (Scan the code on my computer) and "Other ways to connect" for the rest.
- **Structure:** sub-pages use Android's back arrow (48 dp). Access tiles only open their page (each group's switch is at the top of that page), and Full ADB sits under its own "Most access" caption. Activity is one plain list per day with hairlines between lines. The AI apps tab is named "AI apps" (was "Connect"). Settings keeps one home for permissions, and long disclosures live in ⓘ sheets (Privacy, Reading unlabeled buttons). Subtitles are never cut short.
- **Touch:** session lengths, filter chips, ⓘ buttons, back, and text buttons are at least 48 dp.

**Threat review:** no new data, permission, or network path. The approval change makes the quick tap the narrower grant. Every answer the owner had is still one tap further at most, and the AI still cannot answer (the card's bounds and the touch filter are unchanged). The floating card's border now always draws, so it cannot pass as part of the app underneath. Full ADB (ADR-046) is unchanged: only its place on the Access page is clearer, and no command filter or confirmation is added.

**Checks:** the app builds with warnings as errors. Unit tests pass, including `SessionSummaryTest`. The screen snapshots (`ScreenSnapshotTest`) were rendered before and after in both themes. *Needs phone:* the UX gate in chapter 09 (a short study with first-time users), and sunlight legibility of Day.

**Rollback:** visual only; revert the commit. Settings, pairing, and protocol are untouched.

## ADR-063 — Midnight Ember: four tabs, Settings behind a gear, and Stop on every screen
**Status:** accepted (2026-10-21, owner after the redesign preview: "go ahead and make these changes to live app … even the session is active still user will be able to use the app") · **Amends:** ADR-062 (colours, hero card, tab names) · **Protocol:** unchanged

**Problem:** after ADR-062 the app had five tabs, so the bar asked people to choose among five words; Settings is visited rarely. A first cut moved AI apps into Settings too, which hid the one thing new owners come back for. Stop sat only on Home: during a session the owner could open Access or Activity, but stopping meant going back first. The hero card was a dark panel in both themes, so Day still opened on a black slab, and the cool greys felt clinical next to the warm ember brand.

**Decision:**
- **Four tabs, one job each** (amended the same day, owner on the beta: "add the connect tab as well … instead of cluttering the app"): Home, Access, Connect, History (was Activity). Connect holds, in this order, sign-in requests, the AI link with three steps, the connected AI apps, keys, and the connection (Wi-Fi or the internet, keep both connected); the sign-in badge sits on it. Home's "needs you" slot opens it; Home no longer repeats a Connect row, and Settings no longer holds AI apps. Settings opens from a gear on Home, and back or any tab closes it.
- **Stop on every screen:** while a session is connecting, live, paused, or reconnecting, a slim bar sits above the tabs on every screen except Home (which has its own Stop): what the AI may do now in words, and a red **Stop** (48 dp). Tapping the words opens Home. Latch stays fully usable during a session; the floating Stop pill over other apps is unchanged.
- **Colour ("Midnight Ember"):** warm near-black Night (#0C0B0A canvas, #171513 cards, cream #F6F2EC text and main button) and warm white Day. Ember #FF7A45 is the brand and "selected"; teal means the AI can act; amber needs you; `StopRed` #D93A3F with white text means Stop and nothing else (white on it is 4.5:1; it was a dark glyph on #FF5A5A). Cream on the warm black is 16.9:1, secondary text 8:1, faint hints at least 4.4:1. The floating pill, approval card, and AI cursor use the same values.
- **Home:** the greeting and phone name, the gear, then one centred hero card on the theme's own surface, tinted by state: a 112 dp orb, one word, one sentence, the Encrypted and time-left tags, then the one action (Start, or a 64 dp **Stop everything** with Pause and Add time). Tiles read **Can do** and **History**.
- **Shape:** cards and tiles 22 dp corners, the hero 28 dp, buttons 18 dp and at least 56 dp tall.

**Threat review:** no new data, permission, or network path. The session bar only calls the existing `stop()`, the same as the Home button, the notification action, and the floating pill; it cannot start or widen anything. Full ADB (ADR-046) is unchanged, still off by default under Access → Most access, and no command filter or confirmation is added. Sign-in requests are still answered only on the AI apps page.

**Checks:** the app builds with warnings as errors; unit tests and lint pass. *Needs phone:* the chapter 09 UX gate, and Day in sunlight.

**Rollback:** visual and navigation only; revert the commit. Settings, pairing, and protocol are untouched.

## ADR-064 — "Everything" in one tap: every ordinary app, no app-by-app questions
**Status:** accepted (2026-10-21, owner on the R59 beta: "do something so user won't have to provide the access for each and everything … remove the send permission … it will be user only asking ai to do") · **Amends:** ADR-021 (app switches), the Access tab (ADR-062, ADR-063) · **Protocol:** unchanged

**Problem:** sending, posting, and deleting already run without asking in an app the owner switched on (ADR-021). But each app had to be switched on one by one, by a question the first time the AI needed it, and the Access tab offered six groups and a dozen switches before the AI could do much. Owners met a "send?"-style question for every new app and called the setup tiring. The "Everything" preset also included full ADB, which ADR-046 keeps off until the owner turns it on by itself.

**Decision:**
- **One choice, three answers:** Access opens with three big cards, **Only look**, **Look and tap**, and **Everything**, each setting every switch at once. The groups (AI access, Screen & control, Files, Share, Phone controls, While the AI works) fold under **Fine-tune**; Full ADB keeps its own **Most access** place. The same cards are the setup step.
- **Everything** switches on every capability except device info and full ADB, and turns on **every app** (`allApps`, kept on the phone): the AI opens and works in any ordinary app with no first-time question, and sends, posts, and deletes there without asking, as in a switched-on app.
- **What still asks:** apps that may hold money, accounts, or passwords (the existing sensitive-app check) ask once, as before. Payments, installs, Android permission prompts, and account deletion still ask every time unless the owner separately opts in (ADR-022) or turns on Auto mode (ADR-024, now under Fine-tune → AI access). "Ask me before every action" still overrides all of it. Full ADB is never part of a preset; picking a card keeps whatever the owner chose for it.
- **Only look** and **Look and tap** turn every-app off, so each app asks once again; "Switch all off" on the Apps page clears it too.

**Threat review:** the widest new grant is an explicit owner tap on a card that says what it does ("Every app, no questions. Bank apps ask once; payments always ask."). It is not a default: a new install still starts with nothing switched on, and the cards are equal choices. Screen text and remote instructions still cannot choose a card or answer a question. Irreversible money and install actions keep their confirmation (AGENTS.md: payments and installs ask by default). Stop, the session time limit, and the History log are unchanged, so every send is still visible and stoppable.

**Checks:** `AutonomyTest.everyAppCoversOrdinaryAppsButNotMoneyAppsOrPayments`; `SetupStateTest` (presets never include full ADB). The app builds with warnings as errors; unit tests and lint pass. *Needs phone:* WhatsApp send with Everything on (no question), a banking app (asks once), a Play Store install (asks).

**Rollback:** pick Look and tap (every-app off), or revert the commit; the stored `all_apps` flag is then ignored.

