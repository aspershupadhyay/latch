# Contributing to Latch

Thank you for helping. Latch controls people's phones, so correctness and safety come before features.

## Set up

```sh
scripts/cloud-session-setup.sh   # Rust deps, headless Android SDK, interop client (idempotent)
scripts/check.sh                 # everything CI runs
scripts/dev.sh                   # gateway + simulated phone for manual testing
```

No phone is needed for most work: `crates/fake-device` speaks the real protocol.

## Ground rules

1. Read [handbook/README.md](handbook/README.md) and the chapters your change touches. Chapter 17 records the current plan and status.
2. One focused change per pull request; explain the user or operator outcome, the capability and risk, and the evidence (commands and results).
3. Protocol changes start in `crates/protocol`: update the types, regenerate schemas (`LATCH_UPDATE_SCHEMAS=1 cargo test -p latch-protocol --test schema`), add valid **and** invalid fixtures, and update `docs/protocol/v1.md` and the Kotlin parser. Never rename an error code or tool.
4. Safety-relevant changes (policy, redaction, approvals, pairing, stop/revoke) need a test that shows the refused path, and a second reviewer.
5. No new dependency without a note on why, its license, and its maintenance.
6. Never put screenshots, screen text, tokens, or personal data in code, fixtures, logs, issues, or PRs.
7. Contributions that enable credential access, covert monitoring, or bypassing OS security will be declined.

## Style

Rust: `cargo fmt`, `cargo clippy -D warnings`, typed errors, no `unwrap` outside tests. Kotlin: official style, warnings are errors, explicit state models. Comments explain why, not what.
