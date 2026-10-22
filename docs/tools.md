# MCP tools

What an AI app can call once it is connected. The currently shipped ordinary tools below require their capability switches, app consent, and risk-based approvals.

Gateways at protocol 1.20 offer **38 tools**. The first line of `list_devices` says how many yours has.

| Tool | What it does |
|---|---|
| `list_devices` | Lists your paired phones, whether they're online, and what's switched on |
| `observe` | Reads the screen (buttons, text, positions) and, if allowed, takes a screenshot |
| `tap`, `type_text`, `scroll`, `swipe`, `press` | Act on the latest screen reading; each one returns the next reading. `tap` can double-tap, or take the words on the element (`text`, with `near` to pick the row and `repeat` for a quantity) and find, scroll to, and tap it in one call; `swipe` with `hold_ms` drags; `type_text` with `submit` also presses Enter/Search/Send; `press` has back, home, recents, lock screen, screenshot (saved to your gallery), split screen, and the arrow keys (the notification shade stays off limits) |
| `scroll_to` | Scrolls until a text is visible, in one call; when the list ends first, it searches the other way |
| `wait_for` | Waits until a text appears (or disappears), in one call |
| `pinch` | Zooms in or out with two fingers |
| `gesture` | Any touch gesture with up to 10 fingers, each following its own path: rotate, two- or three-finger swipes, curved paths, edge swipes, press-then-drag. A finger that does not move is a tap and asks you exactly like `tap` would. Ends within 15 s; needs Latch 1.18 |
| `list_apps`, `launch_app` | See and open installed apps; `launch_app` with `fresh` starts the app from its first screen |
| `open_link` | Opens a web or app link straight in one app (a YouTube video, a Maps search) instead of tapping there; the same app rules as `launch_app`, message links ask you, and payment links always do |
| `device_status` | Battery and charging, Wi-Fi or mobile data, free storage, orientation, whether the phone is locked, and Do Not Disturb; nothing personal |
| `ask_owner` | Asks you to do a step only a person should do (log in, unlock, a code); waits for **Done** |
| `list_files`, `read_file` | List photos you allowed, Downloads, or the folders you share; look at a text file or picture |
| `get_file_link`, `upload_link` | Private, encrypted 15-minute links to copy whole files (up to 2 GB) between your computer and the phone (openssl + curl). With fast transfers on and both on the same Wi-Fi, the file goes straight between them; `get_file_link` with `lane: "cloud"` always uses the gateway |
| `transfer_status` | Waits for a big file that is still moving |
| `set_clipboard` | Puts text on the phone's clipboard to paste (never reads it) |
| `phone_control` | Sound: volumes, media keys, the ringer, Do Not Disturb. Screen: brightness, rotation, screen timeout. The flashlight. Settings panels and pages (Wi-Fi, internet, Bluetooth, volume, NFC, airplane mode, location, display, battery, sound, notifications, storage, date and time). Each group is its own switch in **Access → Phone controls**, off until you turn it on |
| `get_activity` | Reads the phone's Activity log (apps, actions, approvals, refusals, files, and who did each: AI, you, or Latch), if you switch it on. With `task: "latest"` it replays the latest AI task as a timeline (seconds from the start, approvals and refusals); the app's Activity tab shows the same under **By task** |
| `finish_task` | The AI says it is done: the cursor disappears at once and its summary goes into Activity |
| `diagnose` | Checks whether each phone is ready (connected, session running, app up to date, switches on) and says what you need to fix on the phone |
| `write_file`, `create_folder`, `rename_file`, `delete_file` | Save and organise files; replacing and deleting ask you unless Auto mode is on |
| `share_to_app` | Opens any app's Share screen with files, ready to post or send |
| `run_steps` | Several predictable steps (open an app, search, tap a result) in one call; every step is checked by the same rules |
| `list_skills`, `run_skill`, `save_skill`, `delete_skill` | Saved multi-step tasks on your gateway, run in one call with this time's inputs (a different cart, another contact) |
| `answer_approval` | Passes on your answer, typed in the AI app, to a question the phone is waiting on (only when you allow answering there) |
| `adb` | Full-access ADB (see below): runs one shell command line on the phone and returns its stdout, stderr, and exit code |

## Full-access ADB (`adb`)

Off until you turn it on. In **Access → Full ADB access**, switch it on and pair Android's wireless debugging once (no cable; see [setup](setup.md#optional-full-adb-access-over-wi-fi)). From then on the AI app can run any `adb shell` command line on this phone, with the same device-side power and results as a computer running `adb` ([ADR-046](adr/README.md#adr-046--unrestricted-owner-authorized-adb-over-wireless-mcp)). Latch adds no command allowlist, per-command approval, app or folder scope, output filter, or quota in this mode, and none of the ordinary tools' rules above apply to it. Android's own ADB permissions are the boundary.

| Argument | Meaning |
|---|---|
| `command` | The shell command line, e.g. `pm list packages`, `dumpsys battery`, `logcat -d -t 200` (at most 128 Ki characters) |
| `stdin` | Optional text piped to the command's standard input (at most 512 KiB) |
| `timeout_ms` | Optional deadline you ask for. Without one, Latch sets none: the self-hosted gateway waits as long as the command runs; on Vercel a function can wait at most about 280 seconds |
| `continue_from` | The cursor from a previous answer, to fetch the rest of a very large output. Give this instead of `command` |
| `base64` | Return stdout as base64 of the exact bytes (Latch 1.18). A picture, such as `screencap -p`, comes back as an image the AI can see |
| `background` | Start `command` as a job on the phone and answer at once with its id (Latch 1.20) |
| `job` | A job id: returns what the job wrote since you last collected, and its exit code once it finished. Give this instead of `command` |
| `wait_ms` | With `job`: wait up to this long for the job to finish before answering with what it wrote so far |
| `cancel` | With `job`: end the job |

- **Complete results.** The phone sends large output in frames and the gateway joins them; past 256 KiB in one answer you get a `continue_from` cursor for the rest. Nothing is cut silently. Unread cursors are kept for the four most recent results.
- **Binary output.** Without `base64`, output travels as UTF-8 text. With `base64: true` it is byte-exact, and an image is shown as an image.
- **Runs beside the other tools** (Latch 1.18): a long-running command (say `logcat` without `-d`) no longer makes taps and screen reads wait, and several ADB commands can run at once. It ends when it finishes, you press **Stop**, or the AI app gives up (the gateway then cancels it on the phone).
- **Jobs** (Latch 1.20, ADR-049): for a command that runs longer than your AI app or the Vercel gateway waits (a build, a long `logcat`, a big copy), pass `background: true`. It starts on the phone and you get a job id at once; the job keeps running between calls and across reconnects. Call `adb {"job": "<id>", "wait_ms": 60000}` to collect what it wrote since the last call; the answer that has the exit code is the last, and the id is then forgotten. `cancel: true` ends it. The output waits in Latch's private cache on the phone until collected (no size limit but free space) and is deleted once collected, on **Stop**, when you turn full ADB off, and when the app restarts. Binary output: write it to a file in the job and read the file afterwards with `base64`.
- **Stop and revoke.** **Stop** on the phone ends a running command at once. **Turn off** under Full ADB access forgets the pairing on Latch's side; to also revoke the key in Android, open **Developer options → Wireless debugging → Paired devices** and forget Latch, or turn wireless debugging off.
- **Never logged.** Command output is returned only to the AI app that asked and is not written to the phone's Activity log or the gateway's audit list.

The wire format behind these tools is in [protocol/v1.md](protocol/v1.md).
