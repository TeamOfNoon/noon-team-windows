Noon Inno Setup subproject

1. Install Inno Setup 6.3 or later (Inno Setup 7 is also supported):
   https://jrsoftware.org/isdl.php
2. Open MfcEdgeDemo.sln in VS2019 with v142 and installed MFC tools.
3. Select Release x64, then Rebuild Solution.
4. Distribute InnoSetup\bin\x64\Release\MN_NT_Setup.exe (or SN_NT_Setup.exe).

Build order: NoonJava -> MfcEdgeDemo (final appended web payload) -> NoonInnoSetup.
The old NoonInstaller MFC project remains available but is unchecked in all solution configurations.
Its old automatic packaging property is false too.

ISCC.exe is detected in standard Inno Setup 6/7 folders or PATH.
For a custom compiler location, edit InnoSetupSettings.props or set INNO_SETUP_ISCC.
A missing compiler produces a clear build error; no installer is silently skipped.

Setup first offers default installation or custom folder / shortcuts.
Default install is per-user at %LOCALAPPDATA%\Programs\Noon, without elevation.
Desktop and Start Menu shortcuts are selected by default.
Inno Setup handles the installed-app registry entry, upgrades and uninstallation.
The executable is installed as Noon.exe; ProductInfo.props supplies app name and version.
The optional bin\config.js beside the built EXE is copied on first install and preserved on uninstall / upgrades.
Browser profiles and user files are preserved. The app is not launched automatically.
Old CUSTOM MFC-installer installations must be uninstalled before switching to this installer.

Compression: Inno Setup's integrated LZMA2 Ultra64, 1024 MB dictionary,
64 fast bytes, two match-finder threads, one block thread and solid compression.
Inno uses its own installer format; it does not create a 7z-format archive or expose
7-Zip's exact 16 GB solid-block / 80% RAM switches.
The previous 7-Zip files are retained only in the disabled old project.
The new installer does not use / extract 7z.exe, 7z.dll or 7z.sfx.
A large dictionary can require about 1 GB RAM to decompress a large package.
Only the finished app EXE and optional config are packaged, not loose runtime DLLs.

The compiler itself is NOT redistributed in this source archive. Install it once on the build PC.
Inno Setup license information: https://jrsoftware.org/isinfo.php
Source/XML/build-wiring checks were run. ISCC compilation and Windows runtime remain unverified here.

MN/SN packaging:
GetAppType.ps1 reads app_type from raw embedded bin/config.js first, then applies
an available external config override (custom config-path, otherwise bin/config.js).
Generated EXE app-type metadata is the fallback; if absent the default is MN.
MN -> MN_NT_Setup.exe -> %LOCALAPPDATA%\Programs\MN_NT\
SN -> SN_NT_Setup.exe -> %LOCALAPPDATA%\Programs\SN_NT\
The two editions have separate Windows app entries, Start Menu folders and Desktop
shortcuts. Install/update/uninstall operate on the matching edition. The custom
installer and Inno Setup both refuse a folder marked as another edition. Existing
legacy Noon registrations and files are not automatically removed or migrated.
Native Windows/PowerShell/Inno Setup compilation and execution require Windows.

Edition display names:
MN: Mawso3a NooN موسوعة نون
SN: Shamela NooN شاملة نون
The native installer uses this name for its heading, window caption and Windows
installed-app entry. Inno Setup uses it as the default AppTitle as well.
