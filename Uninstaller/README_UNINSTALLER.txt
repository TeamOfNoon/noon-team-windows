NoonUninstaller is an independent static-MFC / static-CRT application project.
Output: Uninstaller\bin\x64\Release\Uninstall.exe
It has no compressed payload, no appended archive, no 7-Zip resources, and no Java/NW.js resources.
Noon_Setup.exe packages both the completed Noon.exe and this Uninstall.exe.
After installation, both EXEs are separate files. Noon.exe does not embed the uninstaller.
The uploaded CHM MFC source files are active in SourceBase; the indexing engine and DAT boot code are removed.
The existing temporary helper behavior for self-removal is retained.
Antivirus results cannot be guaranteed. Windows compilation/runtime remain unverified here.
