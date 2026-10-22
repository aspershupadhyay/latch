# Install the gateway on your computer

You need this only to run Latch's gateway on your own computer, for phones on the same Wi-Fi. With a [Vercel gateway](operations/vercel.md) there is nothing to install.

Every way below installs the same two things: `latch-gateway`, and the `latch` command that starts it, keeps it running, updates it, and removes it. Your keys are made once, on first use, in `~/.latch/latch.env` (`%USERPROFILE%\.latch\latch.env` on Windows), readable only by you. Updating or reinstalling keeps them, and your phones stay paired.

## One line (works today)

**macOS and Linux**, in Terminal:

```sh
curl -fsSL https://github.com/aspershupadhyay/latch/releases/download/beta/install-gateway.sh | sh
```

**Windows**, in PowerShell (no administrator rights needed):

```powershell
irm https://github.com/aspershupadhyay/latch/releases/download/beta/install-gateway.ps1 | iex
```

Both check every file against the published SHA-256 before installing anything. Update later with `latch update`.

## As a package (from v0.1.0)

From the first numbered release, the gateway comes as a package, so you install it by name and update it with everything else:

| System | Install | Update |
|---|---|---|
| macOS, Linux (Homebrew) | `brew tap aspershupadhyay/latch https://github.com/aspershupadhyay/latch`<br>`brew install latch` | `brew upgrade latch`, then `latch restart` |
| Windows (Scoop) | `scoop bucket add latch https://github.com/aspershupadhyay/latch`<br>`scoop install latch` | `scoop update latch` |

This repository is the Homebrew tap and the Scoop bucket ([ADR-068](adr/README.md#adr-068--packages-homebrew-and-scoop-from-tagged-releases-one-latch-command-everywhere)): no second repository and no third party. Each release's workflow builds the archives, writes the formula and the manifest with their checksums, and installs both packages on real macOS and Windows machines before anything is published.

**winget** is not offered yet. It puts only `.exe` files on the PATH, so it would install the gateway without the `latch` command. Its manifests are made with every release and will be submitted once `latch` is built into the gateway itself.

## Use it

```sh
latch start             # start it and show the QR code: in the app, tap Scan the QR code
latch autostart on      # or keep it running in the background, also after a restart
```

Windows asks once whether to let `latch-gateway` through its firewall: allow it on **private networks**, so the phone can reach it. The full walk-through with the phone is in [Connect your phone](connect-your-phone.md).

| Command | What it does |
|---|---|
| `latch start` | Start the gateway and show the QR code for your phone |
| `latch pair [--open]` | A new QR code (`--open`: big, in the browser); also approves phones that use **Find my computer** |
| `latch status` | Running? Version, phones, Wi-Fi address |
| `latch autostart on\|off` | Keep it running in the background (macOS LaunchAgent, Linux systemd user service, Windows Startup folder) |
| `latch stop` · `latch restart` · `latch logs` | Stop it, restart the background gateway, follow its log |
| `latch usb [--watch]` | Phone on a USB cable, for the lowest mirror delay (needs adb and USB debugging) |
| `latch keys` | Where your keys are |
| `latch update` | The newest version, keeping keys and phones (with a package, it tells you the package command) |
| `latch uninstall [--all]` | Remove it; `--all` also deletes your keys and paired phones |

## Remove it

Run `latch uninstall` (add `--all` to delete your keys and paired phones too). With a package, it turns background start off and then hands over: Homebrew removes itself, and on Windows run `scoop uninstall latch` after it. Then remove Latch from your AI app, for example `claude mcp remove latch`.

## By hand

Each release lists `latch-gateway-<system>` and the `latch` files with their checksums in `latch-gateway.sha256` (beta) or `SHA256SUMS` (numbered releases, with [build attestations](operations/release.md#checking-a-release)). Put `latch-gateway` and `latch` (or `latch.ps1` and `latch.cmd`) in one folder on your PATH. On macOS, download with `curl` rather than a browser, which marks the file so that macOS refuses to open it. To build it yourself: `cargo build --release -p latch-gateway`.
