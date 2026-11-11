NOON EMBEDDED WEB EXE
Open MfcEdgeDemo.sln in Visual Studio with the MSVC v142 (Visual Studio 2019) C++ toolset and matching MFC libraries installed. Windows SDK 10.0.19041.0 is selected.
Build Release | x64. The resulting MfcEdgeDemo.exe contains all files under app.
Edit or replace app/dir.html and app/www, then rebuild. The PowerShell prebuild step regenerates resources automatically.
MFC, C/C++ runtime, and WebView2Loader are statically linked. Web assets are served from EXE resources in memory at https://noon.invalid/ and are not extracted.
Microsoft Edge WebView2 Evergreen Runtime must be installed on the target PC. The browser engine is NOT statically included.
User browser data is stored in LocalAppData. Embedded files are read-only. Node/NW.js APIs are not available.
This is a static web host, not a replacement for Java SQLite/Lucene APIs. Those features require an implemented native bridge or a separately provided backend. No Java binaries/databases were supplied in this build.
No antivirus approval or encryption is implied. Windows compilation and UI execution were not possible in this environment.

CACHE CLEANUP
Use Tools > Clear app cache. Clears WebView2 disk cache and Cache API storage through its profile API. Retains localStorage settings/bookmarks, cookies and IndexedDB. It does not delete the profile folder or embedded web resources. New cache files may be created during use.

Tools > Clear all app data asks for confirmation, unloads app frames, clears all WebView2 profile browsing data, and reloads the app. This includes saved settings, bookmarks, cookies, history and browser databases. Embedded resources and external Java/SQLite files are not deleted. The profile directory may remain and new browser files are created during use.

TOOLCHAIN FIX
Resource type 10 (RT_RCDATA) is used explicitly to avoid old resource-compiler interpretation of RCDATA. C++17 options and maybe_unused attributes removed for the VS2015 compiler. The v140_xp toolset does not make WebView2 compatible with Windows XP.

SDK HEADERS
The project explicitly includes Windows Kits/10/Include/10.0.19041.0/winrt for wrl.h and EventToken.h when using v140_xp. Install that Windows SDK if the build reports it missing. For a nonstandard SDK location, set NoonWindows10SdkRoot in the project.

SOURCE CLEANUP
Removed obsolete external batch/server launch, cmd.exe/CreateProcess/job-object code, server URL polling, and manual Edge installation discovery. Embedded web serving and both cleanup options remain. This is a maintainability cleanup, not a validated antivirus fix. Submit any flagged release binary to the antivirus vendor for analysis.

CURRENT TOOLSET: v142 in all Debug/Release and Win32/x64 configurations. Earlier v140_xp notes describe previous revisions.

RAW SEARCH INDEX RESOURCES
This version includes the latest supplied app and its MN33/VB4 search index.
736 JavaScript index wrappers were converted to 709 raw binary files and 27 JSON files. The decoded binary bytes and JSON values were verified against the original archive. Index size changed from 74,482,835 to 56,094,409 bytes.
The folder name whxdata_js is retained because the supplied search loader uses that base path; contents are raw BIN/JSON and res/config.js selects bin.
GenerateWebResources.ps1 also converts future XMLJS.binary/receive index wrappers into GeneratedWebData during builds and embeds those raw files. It does not execute index scripts. Replace the complete index folder when updating; do not mix duplicate .bin and .bin.js files.
Resources remain RT_RCDATA and are served in memory. The existing host returns HTTP 200 for range requests; the supplied search loader handles that response by slicing the requested bytes. No files are extracted at runtime.
VS v142 and the existing cache/data cleanup commands are preserved. Build Release | x64, then scan the resulting executable. Windows compilation, PowerShell execution, WebView2 UI testing and antivirus verification were not available in this Linux environment. Raw packaging does not guarantee antivirus acceptance.
