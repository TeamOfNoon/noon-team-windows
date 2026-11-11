@echo off
setlocal DisableDelayedExpansion
set "NOON_ROOT=%~dp0."
cd /d "%TEMP%"
rem Uninstall.ps1 requests administrator approval before removing anything.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Uninstall.ps1" -InstallRoot "%NOON_ROOT%"
exit /b %ERRORLEVEL%
