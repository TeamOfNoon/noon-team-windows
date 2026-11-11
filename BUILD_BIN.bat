@echo off
setlocal EnableExtensions
cd /d "%~dp0"
set "NOON_JDK=%~1"
if not defined NOON_JDK set "NOON_JDK=%GRAALVM_HOME%"
if not defined NOON_JDK set "NOON_JDK=%JAVA_HOME%"
if not exist "%NOON_JDK%\bin\javac.exe" (
 echo ERROR: Pass a JDK 17 or newer folder to BUILD_BIN.bat.
 exit /b 1
)
call :BuildBinSteps > "%~dp0BUILD_BIN.log" 2>&1
set "NOON_BIN_RESULT=%ERRORLEVEL%"
type "%~dp0BUILD_BIN.log"
if not "%NOON_BIN_RESULT%"=="0" echo ERROR: BUILD_BIN failed. Full details: %~dp0BUILD_BIN.log
exit /b %NOON_BIN_RESULT%

:BuildBinSteps
call "%~dp0PREPARE_SIGNING_KEY.bat" "%NOON_JDK%"
if errorlevel 1 exit /b 1
if not exist "NoonJava\build\nttool" mkdir "NoonJava\build\nttool"
"%NOON_JDK%\bin\javac.exe" -encoding UTF-8 -cp "NoonJava\lib\*" -d "NoonJava\build\nttool" "NoonJava\tools\nt\DtBuilder.java" "NoonJava\tools\nt\DbStateGuard.java" "NoonJava\tools\nt\BuildNt.java" "NoonJava\tools\nt\StageLucene.java"
if errorlevel 1 exit /b 1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0PrepareBin.ps1" -JavaExe "%NOON_JDK%\bin\java.exe"
exit /b %ERRORLEVEL%
