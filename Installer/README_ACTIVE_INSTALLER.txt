The custom MFC installer is active again. Inno Setup is disabled.
Build MfcEdgeDemo.sln / Release x64 / v142.
Output: Installer\bin\x64\Release\MN_NT_Setup.exe (or SN_NT_Setup.exe)
Quick default/custom setup, Desktop/Start Menu shortcuts, Windows installed-app registration and uninstall are included.
Parent C++ sources and headers appear under Parent MfcEdgeDemo source in the installer project. They reference the original files directly.
Those application .cpp files are Excluded From Build in the installer: compiling them would introduce a second CWinApp and unwanted browser/server dependencies.
Installer.cpp uses the parent MFC pch/framework headers; the finished application EXE is compressed into the installer payload.
The parent NW.js/Java/runtime RC files are not included in the installer wrapper.
No Inno Setup compiler installation is required.
Windows compilation and install/uninstall tests remain unverified here.

The compressed 7z archive now ends at EOF. Metadata is stored in resource 6002, not a trailing footer.
Manual extraction with 7-Zip is supported as a portable copy; it does not create shortcuts or uninstall registration.
Setup UI uses Segoe UI, an app-icon header, destination panel and aligned bottom actions.

Extraction uses hidden 7zCon.sfx (CREATE_NO_WINDOW), with no separate 7-Zip window.
The parent installer shows its marquee loader, then one result notice. On success, OK closes setup.

Uninstall runs from Temp, displays its own progress window and one final result notice.
It does not inherit the installed app folder as its working directory.

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
