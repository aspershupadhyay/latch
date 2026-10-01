---
title: "✨ 09 — UI/UX Design System"
source_page: "https://app.notion.com/p/3ec9b3674a8a81d1bebfe8134c5b87f6?pvs=204"
page_id: "3ec9b367-4a8a-81d1-bebf-e8134c5b87f6"
last_fetched: "2026-10-01T21:13:45.538Z"
---

<!-- Mirrored from the MobileMCP Notion handbook. Source page: https://app.notion.com/p/3ec9b3674a8a81d1bebfe8134c5b87f6?pvs=204 -->
<callout icon="✨" color="purple_bg">
	**Visual direction:** calm, sparse, and instrument-like. The interface should feel like a trustworthy control room, with a living field of dots that explains state without shouting.
</callout>
## Design language: Signal Field
The product uses a distinctive visual language called **Signal Field**:
- a quiet neutral canvas;
- one primary accent for active state;
- small dot clusters for connection and activity;
- soft depth through borders and tonal surfaces rather than heavy gradients;
- short, purposeful motion;
- typography that prioritizes legibility over decoration;
- one clear action per screen;
- no fake “AI magic” language.
The dot field is not a decorative clone of another product. It is a state visualization:
- one dot: device ready;
- paired orbit: trusted connection;
- moving arc: command in flight;
- separated dots: disconnected or paused;
- amber ring: awaiting human approval;
- red pulse: stop, revoke, or policy refusal.
Provide a static, non-animated equivalent for reduced motion and assistive technology.
## Information architecture
Primary navigation:
1. **Home** — device status, session state, and the safest next action.
2. **Capabilities** — what the agent can see and do.
3. **Sessions** — active, paused, closed, and recent sessions.
4. **Activity** — readable audit timeline and diagnostic export.
5. **Settings** — pairing, privacy, network, policy, and about.
The app should not hide stop, revoke, or active-session state inside settings.
## Core screens
### Onboarding
- product promise in one sentence;
- local-first and remote-mode explanation;
- explicit privacy summary;
- no permission request before the user understands the feature;
- progressive setup: pair, choose capabilities, run safe test.
### Home
- Signal Field status;
- device name and trust indicator;
- connection mode: local or user-hosted remote;
- active app and session scope;
- large Stop control when active;
- one recommended next action;
- link to capabilities and activity.
### Capabilities
Each capability card shows:
- plain-language meaning;
- data it can expose;
- actions it can perform;
- current platform grant;
- selected app or scope;
- risk badge;
- expiration;
- revoke button;
- documentation link.
Use progressive disclosure. A user should not have to read protocol terms to decide.
### Pairing
- short-lived pairing code;
- device fingerprint;
- gateway name and endpoint;
- clear expiry timer;
- cancel and reset;
- confirmation that no pairing occurred if the user backs out.
### Active session
- persistent Signal Field;
- exact session scope;
- current app;
- latest operation;
- pause and stop controls;
- approval request card when needed;
- connection health without technical noise.
### Confirmation sheet
The title is the action, not “Are you sure?”
Show:
- what will happen;
- where it will happen;
- which data will be used;
- which application is targeted;
- whether this is one-time or repeat;
- expiry;
- Approve, Deny, and Stop options.
### Activity
A timeline with plain language:
- observation;
- policy decision;
- approval;
- action;
- result;
- refusal;
- reconnect.
Default to redacted labels. Let the user reveal more only in a local, deliberate diagnostic view.
### Diagnostics
- connection health;
- permission health;
- protocol and app versions;
- copy-safe logs;
- export preview;
- reset and revoke actions;
- links to troubleshooting.
## Tokens
Use an 8-point spacing base with a small set of semantic sizes. Define colors by meaning, not component:
- canvas;
- surface;
- elevated surface;
- border;
- primary text;
- secondary text;
- accent;
- success;
- warning;
- danger;
- disabled.
Typography needs a readable body size, clear display scale, strong focus ring, and support for large text without clipping. Corners, shadows, and motion should be restrained and consistent.
## Motion
- use motion to show state transitions, not decorate idle screens;
- keep status movement slow and low-contrast;
- animate the dot field only while state changes;
- use spring or ease-out for panel entry;
- never block interaction on animation;
- respect Reduce Motion and system animation settings;
- announce state changes to accessibility services.
## Accessibility
- every action has a text label;
- color never carries meaning alone;
- the active session has a persistent verbal announcement;
- focus order follows the task;
- hit areas are comfortably large;
- dynamic type and screen readers work;
- contrast is checked in every theme;
- error recovery is reachable without gesture precision;
- screen capture and remote-control status are available in text.
## Content tone
Use direct, calm copy:
- “Screen access is off.”
- “This action would send a message from Signal.”
- “The page changed before the tap could be confirmed.”
- “The iPhone is paused because iOS moved the app to the background.”
- “Stop all activity.”
Avoid vague claims such as “the AI is thinking,” “magic automation,” or “fully autonomous.”
## UX acceptance gate
A user study must show that a first-time user can:
- identify current visibility and control scope;
- start and stop a session;
- understand a confirmation;
- revoke one capability;
- distinguish local from remote;
- recover from a refused or unsupported action;
- complete the safe test without reading developer documentation.
