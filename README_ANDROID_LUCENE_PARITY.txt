Desktop Lucene parity with noon_android_binary_stop_words.zip

Build the existing Visual Studio solution normally. New Java sources are included
in the project and picked up by its existing compile and native-image steps.

Changes:
- Android whole-word and root analyzers, including its enlarged token-length limit.
- Android Unicode normalization, digit conversion and domain aliases in indexes,
  queries, snippets and viewer highlights.
- Android positional substring indexing, phrase repetition, All/Any term grouping,
  wildcard search, occurrence ranking and original-date sorting behavior.
- Android stop-word checkbox and sorted binary lookup: enabled by default for
  All/Any, disabled in Phrase, saved in settings, reset to enabled.
- Stop words are removed from the query, never from the indexed content, so
  phrase searching retains every word. Existing res/SW.js is used if supplied;
  a missing default res/SW.js is served automatically. stop_word_path in
  bin/config.js can override the path (relative to www).
- Android search cancellation is connected to desktop HTTP endpoints.

The source version changed so existing indexes are refreshed by the existing
startup synchronization. An older EXE's indexes must be refreshed before assessing
search parity. SQLite files, signing and database structure are unchanged.

Validation:
- All non-Graal desktop Java sources compile against bundled Lucene 5.3 jars.
- 12 regression assertions, including 1- and 300-character search words,
  repeated phrases, Arabic/English normalization, wildcard and partial queries,
  ranking and Boolean queries above 1024 terms.
- 96 complete search responses matched an independently compiled writer from
  the supplied Android source on the same sample corpus.
- Executed stop-word UI checks for Arabic/English filtering, phrase bypass,
  persisted toggle, reset, an all-stop-word query and the search run token.

Windows Visual Studio/GraalVM native build and the actual bundled web UI were
not executable in this Linux environment. The supplied desktop ZIP references
external web/database assets; no SQLite database or web data was changed.
