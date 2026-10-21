# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
#
# Installs the Latch gateway on Windows from the beta release. In PowerShell:
#
#   irm https://github.com/aspershupadhyay/latch/releases/download/beta/install-gateway.ps1 | iex
#
# What it does, like install-gateway.sh on macOS and Linux: downloads
# latch-gateway-windows-x86_64.exe, checks it against the published SHA-256,
# makes the gateway's keys once (kept across updates), and installs the `latch`
# command (latch start, pair, status, autostart, stop, update, uninstall, ...)
# on your PATH. Running it again updates the gateway and keeps keys and phones.
#
# No administrator rights needed. Background start at log-in uses your own
# Startup folder (latch autostart on). Windows asks once to allow the gateway
# through its firewall: allow it on private networks so the phone can reach it.
#
# Options (environment): LATCH_INSTALL_DIR (default %LOCALAPPDATA%\Latch\bin),
# LATCH_HOME (default %USERPROFILE%\.latch), LATCH_RELEASE (default the beta
# download URL).

& {
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'  # Invoke-WebRequest is many times faster without it
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

    $release = if ($env:LATCH_RELEASE) { $env:LATCH_RELEASE } else { 'https://github.com/aspershupadhyay/latch/releases/download/beta' }
    $binDir = if ($env:LATCH_INSTALL_DIR) { $env:LATCH_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'Latch\bin' }
    $latchHome = if ($env:LATCH_HOME) { $env:LATCH_HOME } else { Join-Path $env:USERPROFILE '.latch' }

    if (-not [Environment]::Is64BitOperatingSystem) {
        Write-Host 'Latch needs 64-bit Windows.' -ForegroundColor Red
        return
    }
    $file = 'latch-gateway-windows-x86_64.exe'

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("latch-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Force $tmp | Out-Null
    try {
        Write-Host "Downloading $file ..."
        Invoke-WebRequest -UseBasicParsing -Uri "$release/$file" -OutFile (Join-Path $tmp $file)
        Invoke-WebRequest -UseBasicParsing -Uri "$release/latch-gateway.sha256" -OutFile (Join-Path $tmp 'latch-gateway.sha256')

        $line = Get-Content (Join-Path $tmp 'latch-gateway.sha256') | Where-Object { $_ -match "\s\*?$([regex]::Escape($file))$" } | Select-Object -First 1
        $expected = if ($line) { ($line -split '\s+')[0].ToLowerInvariant() } else { '' }
        $actual = (Get-FileHash -Algorithm SHA256 (Join-Path $tmp $file)).Hash.ToLowerInvariant()
        if (-not $expected -or $expected -ne $actual) {
            Write-Host 'The download does not match the published checksum; nothing was installed.' -ForegroundColor Red
            return
        }

        # Windows cannot replace a program that is running: stop the old gateway first.
        $pidFile = Join-Path $latchHome 'background.pid'
        if (Test-Path $pidFile) {
            Stop-Process -Id ([int](Get-Content $pidFile)) -Force -ErrorAction SilentlyContinue
        }
        Get-Process -Name 'latch-gateway' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 500

        New-Item -ItemType Directory -Force $binDir | Out-Null
        $exe = Join-Path $binDir 'latch-gateway.exe'
        Copy-Item -Force (Join-Path $tmp $file) $exe
        Unblock-File $exe -ErrorAction SilentlyContinue
        & $exe --version | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Host 'The gateway did not start (latch-gateway --version failed).' -ForegroundColor Red
            return
        }
    } finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }

    # Keys, made once and kept. Only this user may read them.
    New-Item -ItemType Directory -Force $latchHome | Out-Null
    $envFile = Join-Path $latchHome 'latch.env'
    if (-not (Test-Path $envFile)) {
        $admin = (& $exe gen-token).Trim()
        $mcp = (& $exe gen-token).Trim()
        @(
            '# Latch gateway keys. Keep this file private.'
            "LATCH_ADMIN_TOKEN=$admin"
            "LATCH_MCP_TOKEN=$mcp"
            "LATCH_DATA_DIR=$(Join-Path $latchHome 'data')"
            'LATCH_WIFI=1'
        ) | Set-Content -Encoding ASCII $envFile
        & icacls.exe $latchHome /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F" | Out-Null
    }

    # `latch`: one command for everything, as on macOS and Linux.
    $latchPs1 = @'
# SPDX-License-Identifier: AGPL-3.0-only
# Latch gateway on this computer. Run `latch help` for the commands.
$ErrorActionPreference = 'Stop'
$bin = '@BIN@'
$latchHome = '@HOME@'
$release = '@RELEASE@'
$exe = Join-Path $bin 'latch-gateway.exe'
$envFile = Join-Path $latchHome 'latch.env'
$pidFile = Join-Path $latchHome 'background.pid'
$log = Join-Path $latchHome 'gateway.log'
$startup = Join-Path ([Environment]::GetFolderPath('Startup')) 'Latch gateway.vbs'

function Load-Keys {
    if (-not (Test-Path $envFile)) { return }
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*([A-Z_]+)=(.*)$') { Set-Item -Path "Env:$($Matches[1])" -Value $Matches[2] }
    }
}

function Background-On { Test-Path $startup }

function Background-Running {
    if (-not (Test-Path $pidFile)) { return $false }
    $null -ne (Get-Process -Id ([int](Get-Content $pidFile)) -ErrorAction SilentlyContinue)
}

function Stop-All {
    if (Test-Path $pidFile) {
        Stop-Process -Id ([int](Get-Content $pidFile)) -Force -ErrorAction SilentlyContinue
        Remove-Item -Force $pidFile -ErrorAction SilentlyContinue
    }
    # One started by `latch start` in a window, too.
    Get-Process -Name 'latch-gateway' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}

function Start-Background {
    if (Background-Running) { return }
    $ps = (Get-Command powershell.exe).Source
    Start-Process -WindowStyle Hidden -FilePath $ps -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden', '-File', "`"$PSCommandPath`"", 'run-background') | Out-Null
}

function Show-Usage {
    @"
latch - the Latch gateway on this computer

  latch start            Start it here and show the QR code for your phone
  latch pair [--open]    A new QR code (--open: big, in the browser); also approves
                         phones that use Find my computer
  latch usb [--watch]    Phone on a USB cable: the lowest mirror delay (needs adb, USB debugging)
  latch status           Running? Version, phones, Wi-Fi address
  latch autostart on|off Keep it running in the background, also after a restart
  latch stop             Stop it (background and window)
  latch restart          Stop and start the background gateway again
  latch logs             Follow the background gateway's log
  latch update           Install the newest beta, keeping your keys and phones
  latch uninstall [--all]  Remove the programs (--all: also keys and paired phones)
  latch version          The installed version
"@
}

$cmd = if ($args.Count -gt 0) { $args[0] } else { 'help' }
$rest = @(if ($args.Count -gt 1) { $args[1..($args.Count - 1)] })

switch ($cmd) {
    'start' {
        Load-Keys
        Write-Host ''
        Write-Host '  AI app setup, once (Claude Code):'
        Write-Host "    claude mcp add --transport http latch http://127.0.0.1:8787/mcp --header `"Authorization: Bearer $env:LATCH_MCP_TOKEN`""
        if (Background-Running) {
            Write-Host ''
            Write-Host '  The gateway already runs in the background (latch autostart).'
            & $exe pair @rest
        } else {
            & $exe serve @rest
        }
        exit $LASTEXITCODE
    }
    'pair' { Load-Keys; & $exe pair @rest; exit $LASTEXITCODE }
    'usb' { Load-Keys; & $exe usb @rest; exit $LASTEXITCODE }
    'status' { Load-Keys; & $exe status; exit $LASTEXITCODE }
    'run-background' {
        # Started hidden by `latch autostart on` and at log-in: keeps the gateway running, like launchd or systemd would.
        Load-Keys
        $ErrorActionPreference = 'Continue'
        Set-Content -Encoding ASCII $pidFile $PID
        while ($true) {
            # cmd does the redirect: plain text in the log, and the gateway's stderr never counts as a PowerShell error.
            & cmd.exe /d /c "`"$exe`" serve >> `"$log`" 2>&1"
            Start-Sleep -Seconds 3
        }
    }
    'autostart' {
        $what = if ($rest.Count -gt 0) { $rest[0] } else { 'status' }
        switch ($what) {
            'on' {
                $ps = (Get-Command powershell.exe).Source
                $line = "CreateObject(""WScript.Shell"").Run """"""$ps"""" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """"$PSCommandPath"""" run-background"", 0, False"
                Set-Content -Encoding ASCII $startup $line
                Stop-All
                Start-Background
                Write-Host "The gateway now runs in the background and starts when you log in. Log: $log"
            }
            'off' {
                Remove-Item -Force $startup -ErrorAction SilentlyContinue
                Stop-All
                Write-Host 'The gateway no longer runs in the background.'
            }
            'status' { if (Background-On) { Write-Host "on (log: $log)" } else { Write-Host 'off' } }
            default { Write-Host 'usage: latch autostart on|off|status'; exit 2 }
        }
    }
    'stop' { Stop-All; Write-Host 'Stopped.' }
    'restart' {
        Stop-All
        if (Background-On) { Start-Background; Write-Host 'Restarted in the background.' }
        else { Write-Host 'Stopped. Start it with: latch start (or latch autostart on)' }
    }
    'logs' {
        if (Test-Path $log) { Get-Content -Tail 100 -Wait $log }
        else { Write-Host "No background log yet ($log). With latch start, the log is in that window." }
    }
    'update' {
        Write-Host "Updating from $release ..."
        $env:LATCH_INSTALL_DIR = $bin
        $env:LATCH_HOME = $latchHome
        $env:LATCH_RELEASE = $release
        # The installer stops the old gateway and, when background start is on, starts the new one.
        Invoke-Expression (Invoke-RestMethod -UseBasicParsing "$release/install-gateway.ps1")
        Write-Host 'If latch start runs in a window, stop it (Ctrl+C) and start it again.'
    }
    'uninstall' {
        $all = $rest -contains '--all'
        if (-not $all -and (Test-Path $latchHome) -and [Environment]::UserInteractive) {
            $answer = Read-Host "Also delete your keys and paired phones in $latchHome? Phones must then be linked again. [y/N]"
            $all = $answer -match '^(y|yes)$'
        }
        Remove-Item -Force $startup -ErrorAction SilentlyContinue
        Stop-All
        Start-Sleep -Milliseconds 500
        # latch.cmd is still running this script: it deletes itself once we return (see the installer).
        Remove-Item -Force (Join-Path $bin 'latch-gateway.exe') -ErrorAction SilentlyContinue
        if ($all) { Remove-Item -Recurse -Force $latchHome -ErrorAction SilentlyContinue; Write-Host 'Removed the gateway, its keys, and its data.' }
        else { Write-Host "Removed the gateway. Your keys and phones stay in $latchHome (the installer brings it back)." }
        $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        if ($userPath) {
            [Environment]::SetEnvironmentVariable('Path', (($userPath -split ';') | Where-Object { $_ -and $_ -ne $bin }) -join ';', 'User')
        }
        Write-Host 'Also remove Latch from your AI app, e.g.: claude mcp remove latch'
        Remove-Item -Force $PSCommandPath -ErrorAction SilentlyContinue
    }
    { $_ -in 'version', '--version', '-V' } { & $exe --version; exit $LASTEXITCODE }
    { $_ -in 'help', '--help', '-h' } { Show-Usage }
    default { Write-Host "unknown command: $cmd"; Show-Usage; exit 2 }
}
'@
    $latchPs1 = $latchPs1.Replace('@BIN@', $binDir.Replace("'", "''")).Replace('@HOME@', $latchHome.Replace("'", "''")).Replace('@RELEASE@', $release.Replace("'", "''"))
    Set-Content -Encoding UTF8 (Join-Path $binDir 'latch.ps1') $latchPs1
    # latch.cmd lets `latch` run from PowerShell and the Command Prompt, whatever the script policy.
    # After `latch uninstall` removed latch.ps1, it removes itself with the (goto) idiom, so cmd
    # never tries to read a batch file that is gone.
    @(
        '@echo off'
        'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0latch.ps1" %*'
        'set "code=%errorlevel%"'
        'if not exist "%~dp0latch.ps1" (goto) 2>nul & del "%~f0"'
        'exit /b %code%'
    ) | Set-Content -Encoding ASCII (Join-Path $binDir 'latch.cmd')

    # On the PATH, for new windows and for this one.
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if (-not (($userPath -split ';') -contains $binDir)) {
        [Environment]::SetEnvironmentVariable('Path', ((@($userPath, $binDir) | Where-Object { $_ }) -join ';'), 'User')
    }
    if (-not (($env:Path -split ';') -contains $binDir)) { $env:Path = "$env:Path;$binDir" }

    # Background start was on: bring the gateway back now, on the new version.
    $startupVbs = Join-Path ([Environment]::GetFolderPath('Startup')) 'Latch gateway.vbs'
    if (Test-Path $startupVbs) {
        Start-Process -FilePath 'wscript.exe' -ArgumentList "`"$startupVbs`"" | Out-Null
        Write-Host 'The background gateway runs the new version.'
    }

    Write-Host ''
    Write-Host "Installed $(& $exe --version) in $binDir"
    Write-Host 'Start it with:  latch start   (then in the Latch app: Scan the code, or Find my computer)'
    Write-Host 'When Windows asks, allow latch-gateway on private networks, so your phone can reach it.'
    Write-Host 'Or keep it running in the background, also after a restart:  latch autostart on'
    Write-Host 'Everything else:  latch help   (status, update, uninstall, logs, ...)'
}
