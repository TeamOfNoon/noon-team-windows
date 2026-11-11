EXE generator
=============
The same options apply to every EXE; there is no parent/child behavior.

Main dialog:
- App info: name, version, copyright.
- App type: MN or SN; saved into config.js and generated EXE metadata.
- config_path: settings folder; defaults to bin/. Browse to change it.
- Mode: Embedded or External.
- Secure: require signed default external bin resources.
- Require password: require the chosen password for external content.
- Password: text field, defaults to noon; editable and enabled with Require password.
- Boot file: editable, defaults to dir.html. If the chosen file is missing, dir.html is used.
- HTTP port number.
- Output EXE and app folder.
More settings contains icons and browser selection.
Extract EXE contents opens a separate extraction/index generation dialog.

The selected config folder is checked first. bin/ beside the EXE remains the fallback.
Custom settings stay authoritative when default bin supplies the content.
The custom config's server_port takes priority; default bin/config.js supplies a
fallback when the custom file is absent. An external port overrides the stored port.
Relative config folders resolve beside the EXE; external_app_path is relative to
the selected config folder. Embedded mode stores fallback resources inside the EXE. An available authorized
external app is selected first; without external content it uses the embedded app.
External settings such as server_port remain supported.

External resources:
Auto detects NT/app.dat + NT/sqlites, app + db, or static dir.html under bin/app or bin.
external_content_type may be auto, default (native SQLite/Lucene), or static.
external_app_path optionally chooses the web folder relative to the config folder.
Secure ON accepts only a signed default NT/app.dat layout and verifies protected
files and signatures. Config secure:false cannot disable the generated Secure setting.
Require password ON accepts pass in config.js or -pass on the command line. It uses
the password entered in the generator. Require password OFF needs no password.
Authorization fields are omitted from browser config responses.

Help opens immediately with a full built-in config template and current form values.
It does not load Java, read config files or create temporary preview files.
Update endpoints are blank in the template; enter your deployment URLs if needed.
Export config.js saves that same config. Require password ON includes the typed
password as pass; OFF removes pass/password/external_password from generated configs.
Build generation retains existing application and nested UI settings.
Help/export provide a template; they do not overwrite the active config.

Build persistence:
Port, version and boot file are stored as server_port, app_version and boot_file. External builds update
these in the chosen config.js while preserving other fields. Embedded builds also
update raw embedded bin/config.js, alongside EXE metadata.
Name/version/copyright are saved in EXE metadata.

Validation:
Java compilation passed. Checks covered independent flags, custom password acceptance
and rejection, signed content and tamper rejection, settings priority and default bin
fallback, and external port priority. Portable C++ checks covered the visible settings,
password input, separate extraction layout and two config exports.
Windows MFC/WebView2/GraalVM native build and visual execution remain untested here.
Signing and verification scripts retain the fixed Java source-path configuration.

External mode labels the app folder as External path (default bin/), separate from
config_path. Embedded mode labels the same source field App folder to embed.

Extract signed bin copies original embedded files without conversion, including
NT/app.dat, NT/sqlites and the existing manifest/signature. Choose an empty
destination folder. This requires an EXE containing the signed embedded payload.

Signed-bin extraction verifies the completed staging folder with the EXE trusted
key before publishing it: signature, protected file list, sizes and SHA-256 hashes.
Missing/modified files fail extraction; incomplete output is removed.

Runtime folders use the executable filename without .exe (prefix rules below):
%LOCALAPPDATA%/<exe-name>/temp/ for native and NW.js temporary runtime files.
%LOCALAPPDATA%/<exe-name>/cache/WebView2/ and cache/NWJS/ for browser profiles.
Browser cleanup targets only those current-app profiles. Older shared profiles
are left untouched. Executables with the same filename share this browser root.
Persistent embedded databases/indexes retain their existing separate data layout.

Edition-aware runtime paths:
App type is read first from raw embedded bin/config.js, then overridden by the
selected external config.js when available and containing a valid MN/SN value.
Missing custom config falls back to bin/config.js. Missing app_type retains the
embedded type; generated EXE metadata then MN supplies the initial default.
MN: %LOCALAPPDATA%/<exe-name>_mn/temp/ and cache/.
SN: %LOCALAPPDATA%/<exe-name>_sn/temp/ and cache/.
The selected runtime root stays fixed for the process lifetime.
The GUI App type selector also controls app_type in Help/export and both
embedded raw bin/config.js and external config generated by the builder.

More settings -> Custom folder (optional) sets an exact folder name under
LOCALAPPDATA for both temp/ and cache/. Example CustomFolder uses
%LOCALAPPDATA%/CustomFolder/temp/ and cache/, with no automatic suffix.
Leave blank for <exe-name>_mn or <exe-name>_sn. The custom name is stored in
the generated EXE metadata. Full paths and invalid Windows folder names are rejected.
Clearing the field restores automatic naming for the new build.

Installer detection uses the same embedded-first/external-override app_type rule.
MN and SN packages install independently to Programs/MN_NT and Programs/SN_NT
under LOCALAPPDATA, with edition-specific setup filenames and registration.

NW.js honors the same runtime folder naming/custom override as Edge. Legacy
--data-path and current --user-data-dir use the app-specific cache/NWJS folder;
child-process TEMP/TMP and extracted NW.js files use the app-specific temp folder.

External detection also runs for Embedded builds. A valid bin/NT/app.dat, bin/app
or static bin web folder is detected automatically; pass alone is sufficient
to authorize it when it matches the required password. external_app_path and
external_content_type remain optional. Password and signature policy are retained.
Invalid external config produces an error instead of silently selecting embedded
resources. No external app leaves the embedded payload available as fallback.

Edition isolation: custom folder names are base names and always receive _mn or _sn.
An existing _mn/_sn suffix is replaced with the detected edition suffix.
Both WebView2 and NW.js use this root for cache, profile, cleanup and temp files.

FULL GUI CONFIGURATION HELP
Help now displays the configuration reference plus the exportable template.
Includes browser/toolbar/menu, external content/security, server, app type,
search engine aliases, web settings, updates, forum defaults/overrides,
legacy arrays, generated index state and nested UI switches.
Export saves only config.js, preserving the current GUI values and password policy.
The external www/res/config.js is not included in this source archive; its
additional app-specific fields remain defined by the selected web assets.
Validation: standalone C++ help/template functions compiled; exported JS
executed for password ON/OFF and checked GUI value propagation.
Full MFC build and Windows dialog interaction require Windows/Visual Studio.
