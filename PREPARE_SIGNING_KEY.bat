@echo off
setlocal EnableExtensions
cd /d "%~dp0"
set "NOON_SIGN_JDK=%~1"
if not defined NOON_SIGN_JDK set "NOON_SIGN_JDK=%GRAALVM_HOME%"
if not defined NOON_SIGN_JDK set "NOON_SIGN_JDK=%JAVA_HOME%"
if not exist "%NOON_SIGN_JDK%\bin\javac.exe" (
 echo ERROR: Pass a JDK 17 or newer to PREPARE_SIGNING_KEY.bat.
 exit /b 1
)
if not exist "NoonJava\build\signing" mkdir "NoonJava\build\signing"
"%NOON_SIGN_JDK%\bin\javac.exe" -encoding UTF-8 -sourcepath "NoonJava\src" -cp "NoonJava\lib\*" -d "NoonJava\build\signing" "NoonJava\tools\signing\SignBin.java" "NoonJava\tools\signing\VerifySignedBin.java" "NoonJava\src\SignedBinIntegrity.java" "NoonJava\src\EmbeddedWebResources.java" "NoonJava\src\EmbeddedIndexInput.java"
if errorlevel 1 exit /b 1
"%NOON_SIGN_JDK%\bin\java.exe" -cp "NoonJava\build\signing;NoonJava\resources;NoonJava\lib\*" SignBin prepare "%CD%"
exit /b %ERRORLEVEL%
