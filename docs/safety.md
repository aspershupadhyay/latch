# Safety and privacy

We built Latch so the AI can't sneak around you:

- **Everything starts off.** You switch on each power yourself.
- **Sessions end on their own** when the timer runs out (15 minutes to 8 hours; tap **+** next to Stop to extend), and the red **Stop** button is always on screen.
- **Only the apps you pick.** The AI can use an app only after you switch it on (or say yes when it first asks). In apps you switched on it doesn't ask again; anywhere else, before the AI taps things like *Send, Post, Call, Delete,* a card pops up on your phone. Tap **Allow once**, or save your answer: **This session**, or **Always in Instagram** (for example).
- **Money, installs, permissions, and deleting accounts ask.** Buttons like *Pay, Buy, Transfer, Install,* Android permission prompts, and *Delete account* ask every time, unless you yourself switch on **Also allow payments and permissions** (off until you do).
- **The phone double-checks.** Even if the gateway missed a risky button, the phone checks the same rules on what it sees right now and asks you anyway.
- **Passwords, PINs, one-time codes, and card numbers are hidden** from the AI, and it can't type into those boxes.
- **Off-limits areas:** the AI can't touch the Latch app itself, your notification shade, or your lock screen.
- **No sneaky stuff exists in the app:** no reading notifications, and no file access beyond the folders and photos you share.
- **Full ADB access is the one exception, and it is yours to give.** It stays off until you switch it on *and* pair Android's wireless debugging. Then the AI app gets the same power as a computer running `adb` on your phone, with no per-command check, by design ([ADR-046](adr/README.md#adr-046--unrestricted-owner-authorized-adb-over-wireless-mcp)). Only turn it on for an AI app you fully trust. **Stop** ends a running command at once; **Turn off** (and forgetting Latch under *Wireless debugging → Paired devices*) revokes it. ADB output is never written to any log. **Keep working on mobile data** is a second, separate opt-in: it opens ADB on port 5555 of your phone until it restarts, which other devices on the same network can try too (only with a key you allowed); switch it off to close the port ([ADR-050](adr/README.md#adr-050--full-adb-on-mobile-data-an-opt-in-fixed-port-until-restart)).
- **Fast transfers on the same Wi-Fi (off until you switch them on)** open a listener on your Wi-Fi for one file at a time: a random address that closes after that file or 15 minutes. Only scrambled (encrypted) bytes cross your Wi-Fi, and a changed file is never kept.
- **Mirror on computer is for you, not the AI** (R30, [ADR-051](adr/README.md#adr-051--live-mirror-the-phones-screen-in-the-owners-browser-through-the-self-hosted-gateway)). Android asks you every time before the screen is shared, a notification with **Stop** stays while it runs, and Stop on the phone ends it at once. A computer joins only with the 6-digit code the phone shows (it works once) or the link you copy; both stop working when mirroring ends. The frames pass through your own gateway and are never saved or logged. From the mirror you can't use Latch's own screens or answer its questions, and password fields are typed on the phone itself. AI apps can't open the mirror.
- **Screen text can't boss the AI around.** Text on screen is treated as plain information, never as instructions.

## What gets stored?

- **Your screen is never saved.** A screen reading passes through your gateway on its way to your AI and is deleted right after (within 60 seconds at most). A text-only copy of the latest screen reading (no picture) is kept for about 2 minutes so taps can be checked against the current screen.
- **Your gateway keeps a short activity list:** what kind of action happened and whether it was allowed. No screen text, no pictures. It keeps the last 1,000 entries for up to 30 days.
- **Keys are stored safely:** scrambled (as hashes) on the gateway, and encrypted on your phone.
- **Nothing goes to us.** There's no Latch server, no tracking, and no analytics.
- **Reading unlabeled buttons:** before the AI taps a button with no label (an icon-only Send or Pay), Latch reads the words on it on the phone with Google ML Kit so the button still asks you. The picture and the words never leave the phone, but ML Kit sends Google usage statistics (phone model, app version, speed). Switch it off in **Settings → Read unlabeled buttons**; then ML Kit never runs.
- **One honest caveat:** whatever your AI sees is sent to that AI company (like Anthropic or OpenAI), because that's how it reads your screen. Their privacy rules apply. Only switch on what you're comfortable sharing.

More detail: [SECURITY.md](../SECURITY.md) and [handbook chapter 08](../handbook/08-safety-privacy-security.md).
