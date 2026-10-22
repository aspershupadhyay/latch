@echo off
rem Latch: runs latch.ps1 from PowerShell or the Command Prompt, whatever the script policy.
rem After `latch uninstall` removed latch.ps1, it removes itself with the (goto) idiom,
rem so cmd never tries to read a batch file that is gone.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0latch.ps1" %*
set "code=%errorlevel%"
if not exist "%~dp0latch.ps1" (goto) 2>nul & del "%~f0"
exit /b %code%
