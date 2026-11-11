VS2019 v142 / Release x64
=========================
Build MfcEdgeDemo.sln with the existing GraalVM setup and NuGet restore.
NW.js 0.8.0 ia32 is embedded as resources. No separate NW.js install is needed.
The main EXE remains x64 (NoonJava.dll requires x64); NW.js runs as a 32-bit child.

Config defaults to bin/config.js beside the final EXE.
If missing, or webview_type is absent, auto is used.
Example JSON:
{ "webview_type": "auto" }
NT_CONFIG JavaScript object properties are also accepted.
Supported values: auto, webview2, nwjs (aliases: nw, node-webkit).
auto: installed WebView2 first; NW.js if absent or environment/controller startup fails.
webview2: WebView2 only; clear error if unavailable.
nwjs: bundled NW.js only.

Custom config:
MfcEdgeDemo.exe --config "D:\Noon\config.js"
MfcEdgeDemo.exe --config="D:\Noon\config.js"
Relative paths resolve beside the EXE. Explicit unreadable paths and invalid
webview_type values report an error. Config code is never executed by the launcher.
This option selects the browser; it does not override web-page NT_CONFIG settings.

NW.js extraction: private %TEMP%\Noon-NW-<pid>-<tick>-<counter> folder.
Extracts nw.exe, required DLLs, nw.pak, credits.html, and a generated package.json.
nwsnapshot.exe is a development tool and is not bundled.
The embedded Java HTTP server serves web assets straight from the parent EXE.
Parent stays alive while NW.js is running, then cleans up its temporary runtime.
No reuse cache is implemented; each NW.js launch uses a new private folder.
NW.js uses its own window, so MFC menus and WebView2-specific controls apply only
to WebView2 mode. Browser profile data is managed by NW.js separately.

NW.js 0.8.0 uses an old browser engine: modern web JavaScript/CSS may not work.
This package has not been compiled or run on Windows in this environment.

Shutdown/readiness revision:
A generated local launcher.html waits up to 15 seconds for HTTP 200 before showing
web content in a full-window iframe (nwdisable/nwfaketop). The iframe has no Node
APIs. The local wrapper uses legacy nw.gui only for close handling.
An explicit window close writes a marker, blanks the frame and calls close(true).
The parent waits for NW.js process exit before stopping the server/cleaning files.
Nonzero exits without a close marker still show an error. Nonzero exits after an
explicit close are recorded in %TEMP%\Noon-NW-exit.log without a popup. This does
not prove the old engine cannot crash during teardown; it distinguishes the user's
close action from an unexpected exit and retains diagnostic exit information.
Windows runtime testing is still required.

NW.js window icon: window.icon = app.ico in the generated package.json.
The app.ico file is embedded from res/MfcEdgeDemo.ico (same icon as main EXE)
and extracted alongside package.json. Replace res/MfcEdgeDemo.ico and rebuild
to change both icons.

For the password-gated GUI and external folders see README_BUILDER.txt.

NW.js app-specific runtime folders
--------------------------------
NW.js and Edge share AppRuntimeRoot: <exe-name>_mn / <exe-name>_sn, or the
exact custom folder stored by the GUI. NW.js extracts to temp/nw-<session>/
and stores its profile under cache/NWJS/.
The bundled Node-Webkit 0.8 uses --data-path. The launcher passes both
--data-path and --user-data-dir to that same profile, plus an explicit
--disk-cache-dir pointing to cache/NWJS/Cache. The package name is derived
from the app root, rather than the old shared noon-embedded identity.
Only the child process receives TEMP/TMP pointing to its app temp session.
The parent environment is retained. Browser cleanup uses the same NWJS profile.
No existing shared profiles are moved or deleted automatically.
Portable regression checks passed; Windows NW.js execution remains untested.

Edition isolation: custom folder names are base names and always receive _mn or _sn.
An existing _mn/_sn suffix is replaced with the detected edition suffix.
Both WebView2 and NW.js use this root for cache, profile, cleanup and temp files.
