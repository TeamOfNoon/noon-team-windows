NOON - NT BUILD + EMBEDDED BIN + LUCENE

Open MfcEdgeDemo.sln and build Release | x64 in VS2019 (v142).
Set NoonGraalVMHome in BuildSettings.props to your GraalVM JDK 17 x64 folder.

BUILD ORDER
The solution builds NoonJava.dll, calls BUILD_BIN.bat without a pause, runs the
donor project's DtBuilder, and appends the resulting gene/bin to noon.exe.
The installer still packages only the finished Noon.exe (no external app/bin).

EDITABLE BUILD INPUTS
  app/             supplied web application (from bin/NT/app.dat)
  db/              donor SQLite databases; keep their original folder names
  templets/        donor SQLite/JS rules
  bin/config.js    supplied desktop configuration

BUILD_BIN.bat creates:
  gene/bin/config.js
  gene/bin/NT/app.dat/...
  gene/bin/NT/sqlites/<form>_sqlite/<form>.sqlite
  gene/bin/NT/templets/...

Only gene/bin content goes into the appended EXE payload. Source app and db
folders are not separately packaged. Web files stay inside the EXE and are read
directly by the localhost HTTP server; private bin data is not served as files.
The source app/www/viewer and index.php trees remain excluded by donor dtbuild.

RUNTIME
Lucene full/partial/root search and background build/status/stop/delete use the
donor Lucene 5.3 code. Installed forms, SQLite-backed viewer JS, index availability
and config overrides are generated at runtime. Existing browser/menu behavior
remains attached to the same local HTTP server.

Writable SQLite/config/template copies are initialized under:
  %LOCALAPPDATA%/Noon/<exe-name>-<path-id>/<embedded-data-id>/bin
They are reused on later runs of the same embedded data. A different embedded
data set gets a different working directory. Web files are NOT extracted there.

Lucene indexes are created OUTSIDE the EXE, exactly beside it:
  <folder containing noon.exe>/bin/lucene/mn33_normal_index
  <folder containing noon.exe>/bin/lucene/mn33_ngram_index
  <folder containing noon.exe>/bin/lucene/mn33_arstem_index
The containing EXE folder must be writable for indexing. Normal setup defaults
to the existing per-user LocalAppData/Programs/Noon installation directory.
Deleting browser cache does not delete SQLite databases or Lucene indexes.

Optional <EXE folder>/bin/config.js (or --config PATH) overrides embedded config.
Lucene HTTP routes are /__lucene/forms, /status, /build, /stop, /delete and
/__search/config, /__search/lucene. Build/delete/stop require POST.
No console builder command or new help entry was introduced.

IMPORTANT: SUPPLIED DATABASE FILES ARE TRUNCATED
The donor ZIP contains these exact damaged files (preserved without repair):
  db/mn24_sqlite/mn24.sqlite: actual 24,082,944; header expects 41,689,088 bytes
  db/mn33_sqlite/mn33.sqlite: actual 32,070,656; header expects 59,883,520 bytes
Both fail SQLite quick_check with 'database disk image is malformed'. Replace
them with complete originals BEFORE building/testing real indexing. The build
copies source SQLite as-is and cannot reconstruct missing database pages.
fo_tree.sqlite passes SQLite quick_check.

VALIDATION
JDK 17 compilation and a valid small SQLite fixture passed: donor dtbuild,
embedded payload reading, private data extraction, forms/config, English full
and partial search, Arabic search, background index creation/deletion, the
EXE-relative bin/lucene output path, persistent data and server shutdown.
Windows VS2019 and GraalVM native-image compilation cannot be run here.
The complete original NWRuntime/nw.exe fix is retained where available.

LUCENE-ONLY PACKAGING: dtbuild excludes every whxdata_js data folder.
Generated www/viewer remains excluded; viewer.html and res/viewer are kept.

INSTALL/UPDATE INDEX SYNCHRONIZATION
Setup preserves the existing bin/lucene directory on reinstall into the same
folder. On the next app launch, existing forum indexes synchronize in a background
worker against the new bundled SQLite data. New and changed posts are updated;
deleted posts are removed; unchanged posts and unchanged databases are skipped.
The /__lucene/status route and existing indexing UI report progress and errors.
First-time users without indexes still select their forms and start indexing.
Cancelling an update rolls back uncommitted changes instead of deleting the
previous indexes. Existing older desktop indexes migrate on their first sync.

SERVER STARTUP SCREEN
Every EXE launch opens /__startup/ before any app HTML. This page is generated
inside NoonJava/src/StartupPage.java, not stored in app/www or gene/bin.
Choose Arabic/English, then the server checks installed databases and updates
existing Lucene indexes. Progress is visible and the app opens automatically
after success. Errors offer retry; an explicit continue option permits opening
without updating after a stopped/failed check. The startup language uses the
same welcome_language and welcome_language_user_selected storage keys as the app.
The logo is a native-image Java resource in NoonJava/resources/noon-startup.
POST /__startup/start begins the check; GET /__startup/status reports stable
progress. Fresh installations without indexes still create them inside the app.

SAVED STARTUP LANGUAGE
The startup page reuses welcome_language from the web app localStorage.
It asks for Arabic/English only when no valid ar/en value exists. Later launches
start checks automatically in the saved language, including changes made inside
the web app. Clearing browser data resets the language choice.

Signing and verification: see README_SIGNED_BIN.txt.
