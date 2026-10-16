# MCP tools

What an AI app can call once it is connected. Every call goes through the same rules: the capability must be switched on, the app must be allowed, and risky actions still ask you on the phone.

| Tool | What it does |
|---|---|
| `list_devices` | Lists your paired phones, whether they're online, and what's switched on |
| `observe` | Reads the screen (buttons, text, positions) and, if allowed, takes a screenshot |
| `tap`, `type_text`, `scroll`, `swipe`, `press` | Act on the latest screen reading; each one returns the next reading. `tap` can double-tap; `swipe` with `hold_ms` drags; `type_text` with `submit` also presses Enter/Search/Send |
| `scroll_to` | Scrolls until a text is visible, in one call |
| `wait_for` | Waits until a text appears (or disappears), in one call |
| `pinch` | Zooms in or out with two fingers |
| `list_apps`, `launch_app` | See and open installed apps |
| `ask_owner` | Asks you to do a step only a person should do (log in, unlock, a code); waits for **Done** |
| `list_files`, `read_file` | List photos you allowed, Downloads, or the folders you share; look at a text file or picture |
| `get_file_link`, `upload_link` | Private, encrypted 15-minute links to copy whole files (up to 2 GB) between your computer and the phone (openssl + curl). With fast transfers on and both on the same Wi-Fi, the file goes straight between them; `get_file_link` with `lane: "cloud"` always uses the gateway |
| `transfer_status` | Waits for a big file that is still moving |
| `set_clipboard` | Puts text on the phone's clipboard to paste (never reads it) |
| `phone_control` | Sets a volume, plays or pauses media, sets the brightness, turns the flashlight on or off, or opens the Wi-Fi, internet, Bluetooth, or volume panel; each group is its own switch in **Access → Phone controls**, off until you turn it on |
| `get_activity` | Reads the phone's Activity log (apps, actions, approvals, refusals, files, and who did each: AI, you, or Latch), if you switch it on. With `task: "latest"` it replays the latest AI task as a timeline (seconds from the start, approvals and refusals); the app's Activity tab shows the same under **By task** |
| `finish_task` | The AI says it is done: the cursor disappears at once and its summary goes into Activity |
| `diagnose` | Checks whether each phone is ready (connected, session running, app up to date, switches on) and says what you need to fix on the phone |
| `write_file`, `create_folder`, `rename_file`, `delete_file` | Save and organise files; replacing and deleting ask you unless Auto mode is on |
| `share_to_app` | Opens any app's Share screen with files, ready to post or send |

The wire format behind these tools is in [protocol/v1.md](protocol/v1.md).
