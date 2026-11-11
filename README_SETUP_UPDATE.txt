Noon Setup: Update and Fresh install
===================================
Setup detects the current per-user Noon installation from the Windows uninstall registry key.
Update is selected by default when the registered Noon.exe exists. Its path is locked.
Fresh install enables the folder field and Browse. The prior managed Noon directory is
removed only after the replacement files and registry entry succeed. Close Noon first.
A failed replacement restores previous files and registration where possible and reports
any remaining backup directory. Setup must run outside the installation directory.
Update preserves bin/lucene. Fresh install starts without the previous index files.
Existing folders without the Noon installation marker cannot be removed as prior installs;
update those in place instead. Unrelated nonempty destination folders are rejected.
The Finish page has an Open Noon after closing setup checkbox, checked by default.
Launch occurs after the setup window closes.

Clear all app data: WebView2 and NW.js return to the server-generated /__startup/ page,
resetting completed startup checks. The language dialog appears only if stored language
was cleared or missing. Opening startup during an active update attaches to that update.
The NW.js boot probe always checks the server-root /__session/boot endpoint.

Validation: portable file replacement/rollback tests and Java HTTP startup checks.
Native Windows GUI/registry integration still requires a VS2019 build and Windows test.

Setup uninstall option
======================
When the registered Noon.exe is present, setup shows Uninstall / إزالة beside
Update. Clicking it starts Uninstall.bat in that registered installation folder.
Setup closes after the BAT is successfully launched. The existing uninstall GUI
handles confirmation, progress, start-menu/desktop removal and registry cleanup.
No second uninstall implementation or additional removal confirmation is added.
If the BAT is missing or launch fails, setup stays open and reports the error.
