@echo off
setlocal
set "NOON_EXE=%~dp0Noon.exe"
if not exist "%NOON_EXE%" set "NOON_EXE=%~dp0MfcEdgeDemo.exe"
rem Use this SAME EXE with an external folder, without rebuilding.
start "" /wait "%NOON_EXE%" --web-mode external --app "%~dp0www" --webview auto --port 4030
exit /b %ERRORLEVEL%
