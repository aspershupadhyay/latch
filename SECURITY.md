# Security policy

Latch is a remote-control boundary for personal phones. We treat every report seriously.

## Reporting a vulnerability

Use GitHub's **private vulnerability reporting** ("Report a vulnerability" on the repository's Security tab). Do not open a public issue. Please include the version, platform and device, the capability involved, reproduction steps, and redacted logs or a fixture. **Never send real screenshots, tokens, passwords, or personal data.**

We aim to acknowledge reports within 3 working days and to agree a disclosure date with you.

## In scope

- Executing an action without the owner's capability, session, or approval (including after Stop, Pause, expiry, or revocation).
- Reading or typing into password, PIN, OTP, or payment fields; leaking redacted content.
- Agents observing or operating Latch itself, the notification shade, or the lock screen.
- Authentication or pairing bypass, token leakage, replay of commands or approvals.
- Screen content, typed text, or tokens appearing in logs, audit events, or error messages.
- Denial of service that prevents Stop from working.

## Out of scope

- A gateway operator reading content passing through a gateway they run (documented trust boundary; self-host).
- Actions the owner explicitly approved.
- Attacks requiring a rooted phone or a compromised OS.

## Current boundaries

See [handbook chapter 08](handbook/08-safety-privacy-security.md) and the risk list in [chapter 17](handbook/17-plan-review-and-revised-delivery.md#6-risks-that-remain).
