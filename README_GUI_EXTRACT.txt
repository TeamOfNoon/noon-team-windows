--gui: Extract app + DB
======================
Enter the existing builder password (noon), then choose Extract app + DB.
Set Extract folder (default: <EXE folder>/bin), or Browse to any custom folder.
The selected folder receives app/ and db/ directly. For the default destination:
  bin/app/                  embedded web app, including dir.html and www assets
  bin/app/www/viewer/        generated viewer tree, forum/thread/post JS and HTML entry pages
  bin/db/                   original embedded SQLite folder layout
  bin/templets/             embedded generation templates
  bin/config.js             embedded runtime configuration, when present

An existing destination is accepted. Only app/ and db/ are replaced, using backups
and rollback if replacement fails. Existing config.js, templets/, lucene/ and other
files are preserved. Missing config/templates are copied from the export.
Extraction works with the app/server still open by using an independent Java
isolate on the builder worker. The running HTTP server is not stopped.
Extraction and viewer generation run on the existing background worker/loader.
The EXE contains SQLite rather than the large generated viewer tree; extraction
regenerates viewer data using the same SQLite viewer generator as the server.
No HTTP server is opened and no Lucene indexes are built during extraction.
Unreadable/unsupported forum databases are copied unchanged and identified in the
completion report when viewer data cannot be generated for them.
Generated HTML entry pages open the restored www/viewer.html with the forum,
thread/post and page parameters.
The export restores files present in the EXE; source files omitted at build time
(such as index.php and old search-index assets) cannot be recovered from it.

Rebuild the full solution, including NoonJava, before running --gui. The native
DLL must contain the new noon_java_export_app_data export. Standalone extracted
web files still require the appropriate server for SQLite/Lucene API requests.
Installer files are unchanged by this extraction-only update.

Offline search indexes in --gui
===============================
Choose index_js or index_bin, then check Full, Partial and/or Stemmed.
Available ranges are read from the app's embedded SQLite folders. Click ranges
in the multi-selection list to include/exclude them; all are selected initially.
Choose Extract + generate indexes to export app/db and generate ONLY the selected
search index types for the selected ranges. Existing app/db replacement keeps the
same staging, backup and rollback behavior as Extract app + DB.
The ordinary Extract app + DB button still exports without generating indexes.
Outputs are inside app/www/whxdata_js/<range>/, in JS wrappers or
binary form according to the chosen transport. Topics are generated for those
ranges and the exported web search config selects the generated transport/types.
Partial indexing uses DatLuceneIndexer.createPositionalNgramAnalyzer directly:
all substring lengths, from one character through the full word, plus the same
whitespace/boundary terms. Arabic/English stems use the same Lucene analyzer too.
No live/external Lucene indexes are changed by offline export.
Rebuild the entire solution to include the additional Java native entry point.

Offline JS/BIN search supports occurrences, relevance, newest and oldest sorting.
Partial queries use full-length substring entries from the Lucene analyzer.
Generated topic text is displayed directly without a second post-file request.
Offline search reproduces the Lucene 5.3 default TF-IDF scoring rules, including
encoded field-length norms, phrase frequency, Boolean coordination and IDF.

Normalization: exported content uses the same preparation helper as Lucene.
The generated reader carries the current Java Unicode normalization tables.
Partial queries also apply the Lucene positional analyzer yeh conversion.
Rebuild and regenerate old JS/BIN indexes after updating the exporter.
Post title and content are concatenated into one indexed text stream for every
selected index type; ordered phrases can span the title/content boundary.

Stable JS/BIN document IDs: Lucene may reorder its internal IDs during segment
merges. The exporter stores the original topic ID as a numeric doc value and
remaps/sorts each posting list before writing JS/BIN. This keeps titles, content,
post IDs and phrase positions associated with the correct search result.
Old generated indexes must be regenerated after rebuilding this version.

The --gui exporter includes ranking checkboxes: Occurrences, Relevance, Newest
and Oldest. All are selected initially; select at least one. Only selected
rankings appear in the exported search options; reset uses the first available
option if Occurrences was not selected. These choices do not alter searchable
text or posting frequencies required by matching and phrase searches.

All four offline ranking options use the Lucene ordering rules. Partial matches
place whole words first for Occurrences and Relevance. Occurrence ties use
Lucene scoring and descending numeric post IDs. Date sorts use original-date
Unix keys from the same database fields and parsing helper as Lucene; missing
original dates sort last. Publication/edit timestamps are not substituted.
Relevance ties use forum/document order as in Lucene. Rebuild and regenerate
JS/BIN indexes to include topics/ranking.json and the internal whole-word data
needed when generating only a Partial index.
