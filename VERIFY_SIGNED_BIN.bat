@echo off
setlocal EnableExtensions
cd /d "%~dp0"
set "NOON_VERIFY_JDK=%~1"
if not defined NOON_VERIFY_JDK set "NOON_VERIFY_JDK=%GRAALVM_HOME%"
if not defined NOON_VERIFY_JDK set "NOON_VERIFY_JDK=%JAVA_HOME%"
set "NOON_VERIFY_BIN=%~2"
if not defined NOON_VERIFY_BIN set "NOON_VERIFY_BIN=%~dp0gene\bin"
if not exist "%NOON_VERIFY_JDK%\bin\javac.exe" (
 echo ERROR: Pass a JDK 17 or newer folder.
 exit /b 1
)
if not exist "NoonJava\resources\noon-integrity\public_key.der" (
 echo ERROR: Trusted public key missing. Build this project first, or provision the trusted public key from the release owner.
 exit /b 1
)
if not exist "NoonJava\build\verify" mkdir "NoonJava\build\verify"
"%NOON_VERIFY_JDK%\bin\javac.exe" -encoding UTF-8 -sourcepath "NoonJava\src" -cp "NoonJava\lib\*" -d "NoonJava\build\verify" "NoonJava\tools\signing\VerifySignedBin.java" "NoonJava\src\SignedBinIntegrity.java" "NoonJava\src\EmbeddedWebResources.java" "NoonJava\src\EmbeddedIndexInput.java"
if errorlevel 1 exit /b 1
"%NOON_VERIFY_JDK%\bin\java.exe" -cp "NoonJava\build\verify;NoonJava\resources;NoonJava\lib\*" VerifySignedBin "%NOON_VERIFY_BIN%"
exit /b %ERRORLEVEL%
