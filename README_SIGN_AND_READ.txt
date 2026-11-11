Build-time SQLite snapshot fix

The full Visual Studio build invokes BUILD_BIN.bat automatically.
Each source SQLite database is opened read-only through SQLite's backup API.
Committed WAL data, when present, is included in a consistent staged snapshot.
Only the staged output is set to DELETE journal mode and checked before signing.
Tables, columns, indexes, triggers and records are preserved. Source databases
are never repaired, rewritten or checkpointed. No records are discarded.
Output paths and folder layout remain the same.

Private-key signing follows successful snapshot creation and validation.
Trusted-public-key verification and runtime immutable reads remain enabled.
The NW.js last-visit iframe fix is included.

Already incomplete/corrupt source databases cannot be repaired by copying or
signing. The build now fails with the exact source path before signing.
The uploaded mn24.sqlite and mn33.sqlite fail this check because pages are
missing. No automatic partial recovery or header edits are performed.

Validated: Java build-tool compilation; live-WAL snapshot with committed rows;
standalone immutable read; source byte preservation; rejection of both uploaded
incomplete MN databases. Full Visual Studio/Windows build remains to be tested.
