# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
#
# latch: the Latch gateway on this computer (Windows). Run `latch help`.
#
# The gateway does the work itself (latch-gateway.exe start, autostart, stop, ...;
# ADR-069), so winget can install it alone as `latch`. This script adds what
# only an install by script or Scoop needs: `latch update` and `latch uninstall`,
# which replace or remove the files. Options (environment): LATCH_HOME,
# LATCH_RELEASE (where `latch update` installs from).
$ErrorActionPreference = 'Stop'
$bin = Split-Path -Parent $PSCommandPath
$exe = Join-Path $bin 'latch-gateway.exe'
# Scoop keeps its manifest next to the files it installed.
$manager = if (Test-Path (Join-Path $bin 'manifest.json')) { 'scoop' } else { 'script' }
$release = if ($env:LATCH_RELEASE) { $env:LATCH_RELEASE } else { 'https://github.com/aspershupadhyay/latch/releases/download/beta' }
$latchHome = if ($env:LATCH_HOME) { $env:LATCH_HOME } else { Join-Path $env:USERPROFILE '.latch' }

$cmd = if ($args.Count -gt 0) { $args[0] } else { 'help' }
$rest = @(if ($args.Count -gt 1) { $args[1..($args.Count - 1)] })

function Invoke-Gateway {
    # The gateway writes its messages to stderr; they are not PowerShell errors.
    $ErrorActionPreference = 'Continue'
    & $exe @args
    exit $LASTEXITCODE
}

switch ($cmd) {
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
        # Background start off, and every gateway stopped, so its files can go.
        $ErrorActionPreference = 'Continue'
        & $exe autostart off 2>&1 | Out-Null
        $ErrorActionPreference = 'Stop'
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
    { $_ -in 'help', '--help', '-h' } { Invoke-Gateway --help }
    default { Invoke-Gateway $cmd @rest }
}
