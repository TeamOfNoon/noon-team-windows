Noon Installer - VS2019 v142, Release x64

Build MfcEdgeDemo.sln using Rebuild Solution / Release / x64.
Build order: NoonJava -> MfcEdgeDemo (including appended web files) -> NoonInstaller -> compression.
Output: Installer\bin\x64\Release\MN_NT_Setup.exe (or SN_NT_Setup.exe)
Noon_Setup_Stub.exe is the unfilled MFC template and is NOT the distributable installer.

Only these supplied 7-Zip files are included in the project:
 Tools\7z.exe and Tools\7z.dll: build-time compression / testing only.
 Tools\7zCon.sfx: small extraction module inside the setup EXE.
 Tools\License.txt: distribution license, also installed beside the app.
The supplied ZIP is a binary release, not 7-Zip source. The MFC setup source is included here.
Source and license details: https://www.7-zip.org/ .

Compression follows the screenshot:
7z format, level 9 Ultra, LZMA2, dictionary 1024 MB, word size 64,
solid block 16 GB, 2 CPU threads, memory limit 80% (-mmemuse=p80).
No password, no volume splitting. Actual memory/compression depends on input size.
The 1024 MB dictionary can require about 1 GB RAM to decompress large archives.

The package contains the completed Noon.exe and license notices.
If bin\config.js exists beside the compiled EXE, it is included too.
Loose Java/NW.js/VC runtime DLLs, development files and build outputs are not added.
The installer wrapper links static MFC/CRT and reuses the parent's MFC pch/framework headers.
It does not link WebView2, GraalVM or NW.js and does not include the parent's runtime RC files.
The compressed application EXE naturally still contains its own embedded runtime/resources.

Installation is per-user, without elevation. Default folder: %LOCALAPPDATA%\Programs\MN_NT or SN_NT.
Existing installation folder contents are replaced on reinstall.
Browse chooses the parent folder, then adds a Noon child folder; edit the path to use another name.
Desktop and Start Menu checkboxes are enabled by default and can be unchecked.
Shortcuts point to the installed EXE and use its icon / folder.
During installation only a temporary hidden 7-Zip console SFX EXE is created (no extraction DLL).
App files extract to a staging folder beside the destination, then move into place on success.
Temporary extraction files are removed afterward; failed extraction removes the staging folder.
A warning reports shortcut failures; the installed app is retained.
Setup starts with Install with defaults / Customize buttons.
Default installation uses the current user folder and enables Desktop / Start Menu shortcuts.
A small Uninstall.exe (installer stub without the compressed archive) is installed.
The app is registered under HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\NoonDesktop_MN or NoonDesktop_SN.
Uninstall is available in Windows Settings > Apps and Control Panel > Programs and Features.
Uninstall.exe can also be opened directly. It asks for confirmation.
Uninstall removes only files recorded during installation, matching app shortcuts, and the registry entry.
It preserves browser profiles and extra user files. Close Noon before uninstalling.
A hidden PowerShell cleanup command removes the temporary uninstall helper after it exits.
If PowerShell is unavailable, the temporary helper can remain in the Windows Temp folder.
Reinstall is allowed; the existing registry entry is refreshed.
No automatic app launch or in-place upgrade is added.

To skip compression when building the installer from MSBuild: /p:NoonBuildInstaller=false
To package a previously built/GUI-generated app manually:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File Installer\BuildInstaller.ps1 -AppExe "D:\MyApp\Noon.exe" -StubExe "Installer\bin\x64\Release\Noon_Setup_Stub.exe" -OutputExe "D:\MyApp\Noon_Setup.exe"

Validation here: project XML, dependency wiring, footer corruption/bounds tests, supplied file hashes.
Windows MFC compilation, 7-Zip compression and installation runtime must be tested on Windows.

The compressed 7z archive now ends at EOF. Metadata is stored in resource 6002, not a trailing footer.
Manual extraction with 7-Zip is supported as a portable copy; it does not create shortcuts or uninstall registration.
Setup UI uses Segoe UI, an app-icon header, destination panel and aligned bottom actions.

Extraction uses hidden 7zCon.sfx (CREATE_NO_WINDOW), with no separate 7-Zip window.
The parent installer shows its marquee loader, then one result notice. On success, OK closes setup.

Reinstall cleans the selected folder after successful staged extraction. It replaces ALL files there.
A temporary backup supports rollback on failure; registration is rewritten for the new installation.
Close Noon before reinstalling. Default path uses the existing registered install location when present.

CURRENT BUILD: NoonUninstaller builds an independent plain EXE. Setup packages it beside Noon.exe.
The old installer-template copying method is no longer used. Noon.exe does not contain Uninstall.exe.
Manual packaging now also requires -UninstallerExe with the path to the compiled Uninstall.exe.

MN/SN packaging:
GetAppType.ps1 reads app_type from raw embedded bin/config.js first, then applies
an available external config override (custom config-path, otherwise bin/config.js).
Generated EXE app-type metadata is the fallback; if absent the default is MN.
MN -> MN_NT_Setup.exe -> %LOCALAPPDATA%\Programs\MN_NT\
SN -> SN_NT_Setup.exe -> %LOCALAPPDATA%\Programs\SN_NT\
The two editions have separate Windows app entries, Start Menu folders and Desktop
shortcuts. Install/update/uninstall operate on the matching edition. The custom
installer and Inno Setup both refuse a folder marked as another edition. Legacy registry entries for the same app are detected by the native installer.
Only a matching legacy registration is retired after a successful update.
Native Windows/PowerShell/Inno Setup compilation and execution require Windows.

Edition display names:
MN: Mawso3a NooN موسوعة نون
SN: Shamela NooN شاملة نون
The native installer uses this name for its heading, window caption and Windows
installed-app entry. Inno Setup uses it as the default AppTitle as well.

Existing-installation detection (native installer):
Checks current edition registration in both 64/32-bit HKCU views, then the legacy
NoonDesktop entry, then Programs/MN_NT or SN_NT and the legacy Programs/Noon folder.
Folder fallback requires Noon.exe plus the installer marker/file list and matching
edition. Legacy edition comes from raw embedded config plus available bin/config.js.
An installed .noon-edition marker is authoritative. The opposite edition, stale
registration and unmarked loose EXEs are not treated as the matching installation.
Opening setup is read-only. Update repairs registration during installation;
uninstall can repair a missing registration for a marked new-edition install.
Legacy installs with no registration should be updated once before uninstalling.

Fresh install (replace folder):
The selected dedicated app folder need not be empty or have an install marker.
Its entire contents are replaced by the new installation. The previous folder
is retained as a transaction backup until installation succeeds; failure rolls
back to its previous contents. Protected Windows/personal roots and a folder
marked as another edition remain rejected. Update keeps its existing behavior.
