# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
#
# Installs the Latch gateway on Windows from the beta release. In PowerShell:
#
#   irm https://github.com/aspershupadhyay/latch/releases/download/beta/install-gateway.ps1 | iex
#
# What it does, like install-gateway.sh on macOS and Linux: downloads
# latch-gateway-windows-x86_64.exe and the `latch` command (packaging/latch.ps1
# and latch.cmd), checks each against the published SHA-256, puts them on your
# PATH, and makes the gateway's keys once (kept across updates). Running it
# again updates the gateway and keeps keys and phones. With Scoop,
# `scoop install latch` from our bucket installs the same files (docs/install.md).
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

    $files = @($file, 'latch.ps1', 'latch.cmd')
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("latch-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Force $tmp | Out-Null
    try {
        Write-Host "Downloading $file ..."
        foreach ($f in $files + 'latch-gateway.sha256') {
            Invoke-WebRequest -UseBasicParsing -Uri "$release/$f" -OutFile (Join-Path $tmp $f)
        }
        $sums = Get-Content (Join-Path $tmp 'latch-gateway.sha256')
        foreach ($f in $files) {
            $line = $sums | Where-Object { $_ -match "\s\*?$([regex]::Escape($f))$" } | Select-Object -First 1
            $expected = if ($line) { ($line -split '\s+')[0].ToLowerInvariant() } else { '' }
            $actual = (Get-FileHash -Algorithm SHA256 (Join-Path $tmp $f)).Hash.ToLowerInvariant()
            if (-not $expected -or $expected -ne $actual) {
                Write-Host "$f does not match the published checksum; nothing was installed." -ForegroundColor Red
                return
            }
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
        Copy-Item -Force (Join-Path $tmp 'latch.cmd') (Join-Path $binDir 'latch.cmd')
        # `latch update` stays on the release this came from.
        $ps1 = (Get-Content -Raw (Join-Path $tmp 'latch.ps1')).Replace('https://github.com/aspershupadhyay/latch/releases/download/beta', $release)
        Set-Content -Encoding UTF8 -NoNewline (Join-Path $binDir 'latch.ps1') $ps1
        Get-ChildItem $binDir | Unblock-File -ErrorAction SilentlyContinue
        & $exe --version | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Host 'The gateway did not start (latch-gateway --version failed).' -ForegroundColor Red
            return
        }
    } finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }

    # Keys, made once and kept (latch.ps1 makes them, readable by you only).
    $latchCmd = Join-Path $binDir 'latch.cmd'
    & $latchCmd keys | Out-Null

    # On the PATH, for new windows and for this one.
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if (-not (($userPath -split ';') -contains $binDir)) {
        [Environment]::SetEnvironmentVariable('Path', ((@($userPath, $binDir) | Where-Object { $_ }) -join ';'), 'User')
    }
    if (-not (($env:Path -split ';') -contains $binDir)) { $env:Path = "$env:Path;$binDir" }

    # Background start was on: bring the gateway back now, on the new version.
    $startupVbs = Join-Path ([Environment]::GetFolderPath('Startup')) 'Latch gateway.vbs'
    if (Test-Path $startupVbs) {
        # `autostart on` again: the Startup entry is rewritten for this version, and the gateway starts.
        & $latchCmd autostart on | Out-Null
        Write-Host 'The background gateway runs the new version.'
    }

    Write-Host ''
    Write-Host "Installed $(& $exe --version) in $binDir"
    Write-Host 'Start it with:  latch start   (then in the Latch app: Scan the code, or Find my computer)'
    Write-Host 'When Windows asks, allow latch-gateway on private networks, so your phone can reach it.'
    Write-Host 'Or keep it running in the background, also after a restart:  latch autostart on'
    Write-Host 'Everything else:  latch help   (status, update, uninstall, logs, ...)'
}
