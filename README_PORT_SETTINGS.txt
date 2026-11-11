Port settings
=============
Index Settings now includes the active port, a port input, Suggest free port,
and Save and restart server. The same controls appear in /__settings/.
The new port is saved as server_port in <EXE folder>/bin/config.js.
Other configuration values and text are preserved.
The new listener binds before replacing the active listener. If the port is
busy or saving fails, the old listener remains running and an error is shown.
After a successful change the page reconnects to the new port and the old
listener closes. SQLite/Lucene workers stay active.
Browser storage is specific to its origin, including the port, so changing
ports can expose a separate language/bookmark/browser-data store.

At startup, a busy port opens a native Arabic/English dialog before the browser
launches. Accept the suggested free port or enter another. The port is saved
after the server binds successfully. Cancel exits startup cleanly.
A native dialog is necessary because the server page is unavailable until bind.
Explicit --port takes precedence over server_port; otherwise the saved value
precedes the legacy port file and builder/default port.

Rebuild the full solution including NoonJava with VS2019/v142.
Windows SDK remains 10.0.19041.0. Installer files are unchanged from this ZIP.
Java compilation/HTTP/config/script tests pass; native Windows build/runtime
verification must be performed on Windows.
