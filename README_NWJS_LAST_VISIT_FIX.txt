NW.js last-visit iframe fix

Cause: index.html, main.html, viewer.html and searcher.html refused to save
nt_last_app_page whenever window.parent differed from window. The NW.js
launcher always displays the app inside an iframe, so no page was saved.

Fix: name the NW.js host iframe noonAppHost and use NTStorage.isAppRoot()
to recognize it. Real nested viewer/search frames still do not overwrite
the containing main.html address. Normal standalone browser behavior remains.

Rebuild the parent Release solution with the changed BrowserLaunch.cpp and
app/www assets included. Both must be rebuilt together. If using an external
web folder or app.dat/bin payload, regenerate/update it from these web assets.
Do not clear browser data; the next visit will save the correct address.

Validation: simulated standalone, NW.js host and nested-frame saving/restoring
for all four pages, including viewer query/hash and nested viewer exclusion.
Windows NW.js launch still requires testing on Windows.
