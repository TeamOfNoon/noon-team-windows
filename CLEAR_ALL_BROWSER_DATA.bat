@echo off
setlocal
set "NOON_EXE=%~dp0Noon.exe"
if not exist "%NOON_EXE%" set "NOON_EXE=%~dp0MfcEdgeDemo.exe"
start "" /wait "%NOON_EXE%" --clearalldata
set "RESULT=%ERRORLEVEL%"
pause
exit /b %RESULT%
