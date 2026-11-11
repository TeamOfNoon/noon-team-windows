HTTP server integration using the Lucene/SQLite-enabled NoonJava DLL

Set GraalVM in BuildSettings.props. Open MfcEdgeDemo.sln and Rebuild
Solution, Release x64. The solution builds NoonJava.dll, embeds the server
DLL and its two VC runtime dependencies, builds the static MFC EXE, and appends the web data.

At startup NoonJava.dll and its two VC runtime dependencies are extracted into a unique folder under the
Windows temporary directory. MFC loads it and creates a GraalVM isolate.
The Java HTTP server reads the appended web files from the original MFC
EXE, without extracting the web files. WebView2 navigates to
http://127.0.0.1:4030/dir.html. Optional port file beside noon.exe overrides
4030. The server stops, DLL unloads, and its folder is removed at normal exit.

The main EXE remains static MFC + /MT. The Java DLL imports VCRUNTIME140.dll
and VCRUNTIME140_1.dll; both are now embedded and extracted beside it.
No MSVCP140.dll is bundled because this Java DLL does not import it.
WebView2 Runtime must also be installed.

Lucene/SQLite dependencies are compiled in as in the supplied clean DLL
project. The optional dependency check is not called at startup. Serving
web files does not open SQLite, so SQLite native extraction is not invoked
by this HTTP startup flow.

Validation: project XML, DLL-only resource/loader consistency, generated
header include paths, HTTP startup/navigation calls, static MFC settings,
and unchanged web payload scripts verified. Java HTTP and dependency JVM
checks passed earlier. Windows build/run and new antivirus scan are pending.
