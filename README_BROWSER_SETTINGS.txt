Server-generated browser settings
=================================
Open the normal app Settings tab: www/index.html?page=settings.
The server appends a generated Browser data section to the settings script response;
no new settings HTML or cleanup script is stored inside app/www.
Direct generated page: http://127.0.0.1:<port>/__settings/
Alias for app/www/index/settings.html: /www/index/settings.html

Buttons:
- Clear cache and browsing history: uses the existing browser context-menu action.
- Clear all browser data: uses the same native confirmation/cleanup path as the menu,
  then returns to the server startup page.
WebView2 dispatches native ID_APP_CLEAR_CACHE / ID_APP_CLEAR_ALL_DATA messages.
Only exact cleanup commands from the active loopback server origin are accepted.
NW.js uses the existing origin-checked shell bridge, clear-data marker and restart.
Saved language controls the panel labels. Normal web browsers cannot perform native
profile cleanup; the buttons are disabled there.
No embedded app files, SQLite sources or Lucene indexes are deleted by these buttons.

Rebuild the full solution including NoonJava and MfcEdgeDemo before using it.
Windows SDK remains 10.0.19041.0 and toolset v142.
Java HTTP tests and page script bridge tests passed. Actual native WebView2/NW.js
execution and the Windows build still need verification on Windows.

The cleanup implementation itself is unchanged; Settings adds command dispatch only.
