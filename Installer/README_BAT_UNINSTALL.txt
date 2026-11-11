Active uninstall method: Uninstall.bat + Uninstall.ps1. No Uninstall.exe is packaged.
Windows installed-apps registration launches the BAT.
PowerShell displays progress while a background runspace removes recorded app files, matching shortcuts and registry entry.
Browser profiles and extra user files remain.
The prior compiled uninstaller project (including the uploaded MFC source) is retained but disabled.
PowerShell and Windows Forms must be available (normally built into supported Windows).
Windows runtime and PowerShell execution are unverified here.

The 7z payload contains ONLY Noon.exe. BAT/PowerShell scripts and license files are RC resources.
Normal installation writes these resources beside the app; manual 7-Zip archive extraction returns only Noon.exe.
No external bin/config.js is automatically included in this payload.
Resources can still be inspected with resource tools; this is packaging separation, not encryption.
