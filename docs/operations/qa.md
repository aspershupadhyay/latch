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
