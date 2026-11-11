External default layout beside the EXE:
NT/config.js
NT/app/dir.html (or NT/app/www/dir.html)
NT/db/
NT/lucene/

Config uses the same w.NT_CONFIG IIFE wrapper as res/config.js.
Explicit --config/--config-path and GUI settings paths remain supported.
Rebuild NoonJava.dll and the EXE on Windows before testing.
Secure mode continues to require the signed NT/app.dat layout relative to the selected settings folder.

Build inputs: NT/config.js, NT/app, NT/db, optional NT/lucene.
PrepareBin.ps1 packages these into gene/bin and gene/bin.7z (generated outputs).
