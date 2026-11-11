Signed bin / الملفات الموقعة
===========================
Build the full VS2019 v142 / x64 solution, or run BUILD_BIN.bat with a JDK 17+.
The build generates, signs and verifies this folder, then embeds those same bytes:

 gene/bin/config.js                           editable
 gene/bin/NT/app.dat/...                       signed web app
 gene/bin/NT/sqlites/...                       signed SQLite files
 gene/bin/NT/templets/...                      signed templates
 gene/bin/NT/noon_public_key.pem               public verification key
 gene/bin/NT/install_manifest.json             signed file paths/sizes/SHA-256
 gene/bin/NT/install_manifest.sig              RSA signature (binary)

No separate bin ZIP is generated. The normal setup packaging is unchanged.
Only gene/bin is embedded; app/db remain source inputs. This is a logical payload,
not a ZIP inside Noon.exe. The web stays embedded; SQLite/templates and signed
metadata are checked after extraction into the existing per-user data cache.
The writable EXE-adjacent bin/config.js remains supported, including server_port.

Private key: BUILD MACHINE ONLY
------------------------------
For overrides, copy SigningSettings.example.properties to SigningSettings.properties beside MfcEdgeDemo.sln. Default key file:
 ../NoonKeys/noon_private.pem
 create_if_missing=true

The first build creates an RSA-3072 PKCS#8 key there if missing. Existing keys are
reused. Keep a secure backup and reuse the same signing key for releases.
Set create_if_missing=false to require an existing key instead of generating one.

Use forward slashes in the properties file. Relative paths are still supported
and resolve from MfcEdgeDemo, NOT the current terminal directory. Examples:
 private_key_path=../NoonSigning/noon_private.pem
 private_key_path=../../NoonSigning/noon_private.pem
 private_key_path=D:/NoonKeys/noon_private.pem

Optional NOON_SIGNING_PRIVATE_KEY overrides the settings-file path and requires
that key to exist. Clear an old environment override if you want the file setting.
RSA PKCS#8 and PKCS#1 PEM keys are accepted (unencrypted, at least 2048 bits).
Encrypted PEM keys are not supported by this unattended build helper.

The key is never copied into gene/bin or the EXE. Private-key copies found in the
staged bin are excluded. Private-key material under NoonJava/resources aborts
preparation. Do not place a private key in source, resources, app, or db folders.
Only the public DER key is generated into:
 NoonJava/resources/noon-integrity/public_key.der
It is compiled as a trusted resource in NoonJava.dll. A different public key placed
inside a writable bin cannot authorize tampered files.

Verification
------------
Run VERIFY_SIGNED_BIN.bat "D:\JDK17" to verify gene/bin, or pass another bin path
as its second argument. It never generates/replaces your trusted public key.
Every signature and protected-file hash is checked before embedding and at app
startup. Startup checks cannot be bypassed with the normal startup-page Skip action.
During serving, file-list/metadata changes are checked and changed files rehashed;
a full disk rehash occurs on a request at least every 30 seconds. Raw protected web
bytes are checked whenever read. After tampering is detected, serving is blocked
until restart. Missing, changed or added protected files all fail verification.
A blocked session returns an HTTP 403 page in Arabic and English. Startup failure
uses the existing native error dialog with the same bilingual message:
 Application files were modified or are not genuine.
 تم تعديل ملفات التطبيق أو أن النسخة غير أصلية.

Writable exclusions: root config.js, lucene/, cache/, userdata/. These exclusions
are not loaded as signed application assets. They allow configuration changes,
port changes and normal index generation without false integrity errors.

Reuse in another project
------------------------
Copy gene/bin as the bin folder and integrate SignedBinIntegrity/VerifySignedBin.
Provision the trusted public DER from the owner separately in the consumer runtime;
do not load a trust anchor from the same untrusted bin being checked. A key pinned
by one release must match the signer. This manifest format is version 1 with
SHA256withRSA and a files array; it is not the older package-download manifest.
Do not assume another Noon/Android version already supports this format.

Signed SQLite files are opened with mode=ro and immutable=1; temporary views
still work. This avoids creating/reading WAL/SHM sidecars for signed snapshots.
Unsigned -wal/-shm files beside a known signed database are ignored as legacy
SQLite runtime artifacts, never consumed as trusted data; all base database
hashes and signatures remain checked. Startup API errors are JSON, and the
startup page displays their actual message. Integrity errors hide Skip/Open.
A source rebuild signs updated web/SQLite files. This project currently has no
signed database-package update writer: replacing SQLite manually is intentionally
rejected. A future updater must accept separately signed owner updates; it must
never blindly trust/re-sign local edits or include the private key in the app.
The existing GUI app/db export is an editable source export, not a signed bin;
rebuild/sign it before serving it as a release. External mode requires the signed
bin beside the EXE and --app pointing to bin/NT/app.dat. Unsigned loose folders
are rejected. A child EXE can reuse the parent's unchanged signed payload, but
new custom web content requires signing on the build machine with the same key.

Limits and validation
---------------------
This detects modified content against the trusted build. It is not encryption or
Windows Authenticode signing, and cannot make an EXE impossible to patch/dump.
Java compilation, genuine/tampered bin checks, private-key exclusion and HTTP
refusal tests were run. Native Image, VS2019 and Windows GUI builds require
Windows verification. Toolset v142 and SDK 10.0.19041.0 are unchanged.
