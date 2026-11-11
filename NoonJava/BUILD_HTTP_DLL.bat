@echo off
setlocal EnableExtensions
cd /d "%~dp0"
if not "%~1"=="" set "GRAALVM_HOME=%~1"
if not "%~2"=="" set "NOON_VS_PATH=%~2"
if not defined GRAALVM_HOME set "GRAALVM_HOME=D:\graalvm-jdk-17.0.18_windows-x64_bin\graalvm-jdk-17.0.18+8.1"
if not exist "%GRAALVM_HOME%\bin\native-image.cmd" (
 echo ERROR: Set GRAALVM_HOME to GraalVM JDK 17 x64 with native-image.
 exit /b 1
)
rem Normal Visual Studio builds do not inherit a developer-prompt environment.
if /i "%VSCMD_ARG_TGT_ARCH%"=="x64" (
 where cl.exe >nul 2>nul
 if not errorlevel 1 goto :tools_ready
call "%~dp0..\PREPARE_SIGNING_KEY.bat" "%GRAALVM_HOME%"
if errorlevel 1 exit /b 1
)
if not defined NOON_VS_PATH call :find_vs
if not defined NOON_VS_PATH (
 echo ERROR: Visual Studio C++ tools not found.
 exit /b 1
)
if not exist "%NOON_VS_PATH%\Common7\Tools\VsDevCmd.bat" (
 echo ERROR: Visual Studio developer environment is missing.
 exit /b 1
)
call "%NOON_VS_PATH%\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64
if errorlevel 1 exit /b 1
:tools_ready
call "%~dp0..\PREPARE_SIGNING_KEY.bat" "%GRAALVM_HOME%"
if errorlevel 1 exit /b 1
where cl.exe >nul 2>nul
if errorlevel 1 exit /b 1
if not exist build mkdir build
if not exist dist mkdir dist
rem Build a shared library from CEntryPoint methods; do not specify a main class.
if exist build\classes rmdir /s /q build\classes
"%GRAALVM_HOME%\bin\java.exe" tools\CompileDesktop.java "%GRAALVM_HOME%" src build\classes
if errorlevel 1 exit /b 1
xcopy "resources\*" "build\classes\" /e /i /y >nul
if errorlevel 1 exit /b 1
call "%GRAALVM_HOME%\bin\native-image.cmd" --shared --no-fallback -H:-CheckToolchain -H:+ReportExceptionStackTraces -H:Name=NoonJava -H:Path="%CD%\dist" --initialize-at-run-time=NoonJavaLibrary,EmbeddedWebResources,EmbeddedWebHttpServer,EmbeddedBin,SignedBinIntegrity,DesktopSearchRuntime,PlainSqliteCatalog,DynamicJs,org.apache.lucene --enable-url-protocols=http -O2 -H:ReflectionConfigurationFiles="%CD%\native-config\reflect-config.json" -H:IncludeResources="noon-integrity/.*|noon-startup/.*|META-INF/services/.*|org/apache/lucene/.*\.txt|org/sqlite/native/Windows/x86_64/.*" -cp "build\classes;lib\*"
if errorlevel 1 exit /b 1
if not exist dist\NoonJava.dll exit /b 1
if not exist dist\NoonJava.h exit /b 1
if not exist dist\graal_isolate.h exit /b 1
echo Built NoonJava.dll and generated C headers.

exit /b 0
:find_vs
set "NOON_VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%NOON_VSWHERE%" exit /b 1
set "NOON_VS_RESULT=%TEMP%\noon-http-vs-%RANDOM%-%RANDOM%.txt"
"%NOON_VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath > "%NOON_VS_RESULT%"
set /p "NOON_VS_PATH=" < "%NOON_VS_RESULT%"
del /q "%NOON_VS_RESULT%" >nul 2>nul
exit /b 0
