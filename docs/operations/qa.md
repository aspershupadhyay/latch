# Developer and QA mode: fake phones, emulators, several phones, Python

Roadmap R15. Everything here uses the same gateway, tools, and rules as a real
owner's setup. There is no "test profile" that relaxes them: an emulator or a
script still gets the owner's approval card, the password-field refusal, and
the Stop pill. That is deliberate. A QA mode that switched safety off would be
the easiest way to ship a regression in it, and it would teach scripts to rely
on behaviour real phones never have.

## 1. Fake phones (no device at all)

`scripts/dev.sh` starts a container gateway (`latch-gateway`) on
`127.0.0.1:8787` and a simulated phone, and prints the two tokens. The fake
phone (`crates/fake-device`) has Settings, a chat app, a shop, and a bank login
with a password field, and answers deterministically, so tests can assert on
its screens.

For **several fake phones**, pair one per name and start one process per code:

```bash
code=$(curl -fs -X POST http://127.0.0.1:8787/v1/admin/pairings \
  -H "Authorization: Bearer $LATCH_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"QA two"}' | sed -E 's/.*"code":"([^"]+)".*/\1/')
target/debug/latch-fake-device http://127.0.0.1:8787 "$code" &
```

With more than one phone connected, every phone tool needs `device_id`
(from `list_devices`). `scripts/interop.sh` does exactly this in CI.

## 2. An Android emulator

Use a **debug** build: it accepts `http://` gateways, and its package
(`io.github.aspershupadhyay.latch.debug`) installs next to a release build.

1. Start a gateway on the computer: `scripts/dev.sh --no-fake`. The emulator
   reaches the computer's `127.0.0.1` as **`10.0.2.2`**.
2. Build and install: `cd apps/android && ./gradlew installDebug` (or
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`).
3. Open Latch on the emulator, go through setup, and connect to
   `http://10.0.2.2:8787` with the owner key (the admin token `dev.sh` printed).
4. Turn on the accessibility service the way an owner does, in the emulator's
   *Settings → Accessibility → Latch*. ADB-installed apps are not marked
   "Restricted setting" (see [android-install.md](../platform/android-install.md)).
   On a **throwaway emulator image only**, a CI job may switch it on with
   `adb shell settings put secure enabled_accessibility_services
   io.github.aspershupadhyay.latch.debug/io.github.aspershupadhyay.latch.accessibility.LatchAccessibilityService`.
   Never do that to someone else's phone: switching it on is the owner's decision.
5. Start a session in the app. The emulator now shows in `list_devices` like any
   phone.

CI does all of this on every push: `scripts/emulator-test.sh` (job
*Android emulator* in `.github/workflows/ci.yml`) boots an Android 14 emulator,
starts a fresh container gateway, and runs `EmulatorGatewayTest`
(`apps/android/app/src/androidTest`). The test pairs with the owner key,
switches the service on as above, starts a session, and then acts as an AI
client over MCP: `list_devices`, `diagnose` (it names battery optimisation
exactly when Android applies it), `launch_app` refused until the owner's
capability switches are on, then `launch_app` Settings, `tap` into
*Network & internet*, and `observe` after **Stop**, which must fail. Run it
locally with the same script on a computer with `/dev/kvm`.

Several emulators work the same way: each one pairs with its own name and
shows up with its own `device_id`.

## 3. The Python client

`adapters/python/latch_client.py` is one file with no dependencies (Python 3.9+).
It speaks MCP over Streamable HTTP to either gateway and returns each tool's
text, error flag, and screenshots:

```python
from latch_client import Latch

latch = Latch("http://127.0.0.1:8787", mcp_token)
for device_id in latch.device_ids():          # connected phones
    phone = Latch("http://127.0.0.1:8787", mcp_token, device_id=device_id)
    s = phone.call("launch_app", package="com.android.settings")
    s = phone.call("tap", observation_id=s.observation_id,
                   element_id=s.element("Network & internet"))
    print(s.text)
```

- One `Latch` instance is one MCP session; give each thread its own to drive
  phones **in parallel** (`test_two_phones_in_parallel`).
- A refusal (`policy_refused`, `sensitive_target`, `stale_observation`, the
  owner said no) is `Result.is_error`, not an exception; only a gateway-level
  problem (wrong token, unknown tool) raises `LatchError`.
- When an approval is needed the call waits for the owner, as for any client.
  Answer it on the phone, or pass the owner's answer with `answer_approval`.

Tests: `adapters/python/test_latch_client.py`. Offline parsing tests always run.
The live tests run in `scripts/interop.sh` (and in CI) against a container
gateway with two fake phones: tool list, two phones in parallel, password
refusal, stale observation, and wrong token.

## 4. Task suites

For measuring how well an AI does real tasks, see [tests/evals](../../tests/evals/README.md):
scripted fake-phone tasks run in CI, and real-phone tasks are run from any MCP
client.

## 5. Robustness: chaos, fuzzing, coverage, soak

Roadmap R29 (e).

- **Chaos run.** `cargo test -p latch-gateway --test e2e under_a_flaky_link` drives forty actions through a fake phone whose link loses answers by dropping the connection, sends answers twice, delivers commands twice, and adds delays, from a fixed seed (`latch_fake_device::Chaos`). Every call must still get its answer, and every action must happen exactly once and in order, on WebSocket and long-poll (ADR-048). It fails if the phone's command memory or the gateway's resend after a reconnect is switched off.
- **Fuzzing.** `fuzz/` holds three cargo-fuzz targets, built on nightly outside the workspace: `gateway_message` and `device_message` parse and validate any bytes, and what parses must survive a round trip; `policy` runs the policy engine on any command with any switches and asserts it never lets through an invalid command, a switched-off capability, or anything during a pause. `fuzz/seed.sh` seeds them with the shared fixtures. Locally: `rustup toolchain install nightly`, `cargo install cargo-fuzz --version 0.13.2 --locked`, then `cd fuzz && ./seed.sh && cargo +nightly fuzz run policy corpus/policy -- -max_total_time=60`. CI (`.github/workflows/fuzz.yml`) runs each for a minute when the protocol, policy, or fixtures change, and half an hour each every week; a crash uploads its input, which then belongs in the fixtures.
- **Coverage.** The Android debug build records JaCoCo coverage of the JVM unit tests: `./gradlew createDebugUnitTestCoverageReport` writes `app/build/reports/coverage/test/debug/`. CI prints the per-package table in the job summary (`scripts/coverage-summary.py`), keeps the report as an artifact, and fails if line coverage drops below 25 % (27.5 % when this was added; policy and protocol are above 85 %, the UI is covered by the emulator test instead).
- **Soak.** `LATCH_SOAK_MINUTES=480 scripts/emulator-test.sh` runs `SoakTest` for eight hours: the real app on an emulator, driven over MCP by a real gateway in a loop (read the screen, open Settings, back, home, clipboard), extending the session as an owner would. It passes when at most 1 % of calls failed, the session is still up, and the app's memory at the end is at most twice what it started with (plus 64 MB); progress and a summary go to logcat under `LatchSoak` (no screen content). GitHub-hosted jobs end at 6 hours, so `.github/workflows/soak.yml` runs 5.5 hours weekly and on demand; the full eight hours needs a machine with KVM.
