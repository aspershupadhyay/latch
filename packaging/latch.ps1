# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
#
# latch: the Latch gateway on this computer (Windows). Run `latch help`.
#
# One file, the same wherever it was installed from: scripts/install-gateway.ps1,
# Scoop, or a release archive. It runs the latch-gateway.exe next to it, makes
# the keys on first use in %USERPROFILE%\.latch\latch.env (readable by you only),
# and keeps the gateway running in the background on request, from your own
# Startup folder (no administrator rights). Options (environment): LATCH_HOME,
# LATCH_RELEASE (where `latch update` installs from).
$ErrorActionPreference = 'Stop'
$bin = Split-Path -Parent $PSCommandPath
$exe = Join-Path $bin 'latch-gateway.exe'
# Scoop keeps its manifest next to the files it installed.
$manager = if (Test-Path (Join-Path $bin 'manifest.json')) { 'scoop' } else { 'script' }
$release = if ($env:LATCH_RELEASE) { $env:LATCH_RELEASE } else { 'https://github.com/aspershupadhyay/latch/releases/download/beta' }
$latchHome = if ($env:LATCH_HOME) { $env:LATCH_HOME } else { Join-Path $env:USERPROFILE '.latch' }
$envFile = Join-Path $latchHome 'latch.env'
$pidFile = Join-Path $latchHome 'background.pid'
$log = Join-Path $latchHome 'gateway.log'
$startup = Join-Path ([Environment]::GetFolderPath('Startup')) 'Latch gateway.vbs'

# Keys, made once and kept across updates and reinstalls. Only this user may read them.
function Ensure-Keys {
    if (Test-Path $envFile) { return }
    New-Item -ItemType Directory -Force $latchHome | Out-Null
    & icacls.exe $latchHome /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F" | Out-Null
    $admin = (& $exe gen-token).Trim()
    $mcp = (& $exe gen-token).Trim()
    @(
        '# Latch gateway keys. Keep this file private.'
        "LATCH_ADMIN_TOKEN=$admin"
        "LATCH_MCP_TOKEN=$mcp"
        "LATCH_DATA_DIR=$(Join-Path $latchHome 'data')"
        'LATCH_WIFI=1'
    ) | Set-Content -Encoding ASCII $envFile
    Write-Host "Made your gateway keys in $envFile (readable by you only)."
}

function Load-Keys {
    Ensure-Keys
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
latch: the Latch gateway on this computer

  latch start              Start it here and show the QR code for your phone
  latch pair [--open]      A new QR code (--open: big, in the browser); also approves
                           phones that use Find my computer
  latch usb [--watch]      Phone on a USB cable: the lowest mirror delay (needs adb, USB debugging)
  latch status             Running? Version, phones, Wi-Fi address
  latch autostart on|off   Keep it running in the background, also after a restart
  latch stop               Stop it (background and window)
  latch restart            Stop and start the background gateway again
  latch logs               Follow the background gateway's log
  latch keys               Where your keys are (made on first use)
  latch update             Install the newest version, keeping your keys and phones
  latch uninstall [--all]  Remove it (--all: also your keys and paired phones)
  latch version            The installed version
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
                Ensure-Keys
                $ps = (Get-Command powershell.exe).Source
                # Does nothing once latch.ps1 is gone (uninstalled without `latch uninstall`), instead of an error at every log-in.
                $vbs = @(
                    'Set fs = CreateObject("Scripting.FileSystemObject")'
                    "If fs.FileExists(""$PSCommandPath"") Then CreateObject(""WScript.Shell"").Run """"""$ps"""" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """"$PSCommandPath"""" run-background"", 0, False"
                )
                Set-Content -Encoding ASCII $startup $vbs
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
    'keys' { Ensure-Keys; Write-Host $envFile }
    'update' {
        if ($manager -eq 'scoop') {
            # Scoop rewrites the `latch` command while it updates, so it cannot run from inside it.
            Write-Host 'Latch came from Scoop. Update it with:  scoop update latch'
            exit 0
        }
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
        if ($all) { Remove-Item -Recurse -Force $latchHome -ErrorAction SilentlyContinue; Write-Host 'Removed your keys and paired phones.' }
        else { Write-Host "Your keys and phones stay in $latchHome." }
        Write-Host 'Also remove Latch from your AI app, e.g.: claude mcp remove latch'
        if ($manager -eq 'scoop') {
            Write-Host 'The background gateway is off. Remove the program with:  scoop uninstall latch'
            exit 0
        }
        Remove-Item -Force $exe -ErrorAction SilentlyContinue
        $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        if ($userPath) {
            [Environment]::SetEnvironmentVariable('Path', (($userPath -split ';') | Where-Object { $_ -and $_ -ne $bin }) -join ';', 'User')
        }
        # latch.cmd is still running this script: it deletes itself once we return (see latch.cmd).
        Remove-Item -Force $PSCommandPath -ErrorAction SilentlyContinue
        Write-Host 'Removed the gateway.'
    }
    { $_ -in 'version', '--version', '-V' } { & $exe --version; exit $LASTEXITCODE }
    { $_ -in 'help', '--help', '-h' } { Show-Usage }
    default { Write-Host "unknown command: $cmd"; Show-Usage; exit 2 }
}
