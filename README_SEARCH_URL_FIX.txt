Search-result URL fix

The Java HTTP server rejects raw braces, pipes, invalid percent signs and other
URI-invalid query characters before the application's request handler runs.
Chromium can send some of these characters in search-result navigation URLs,
causing: 400 Bad Request / URISyntaxException thrown.

The server now inserts a small JavaScript guard before application scripts.
It encodes invalid characters for local link clicks, window.open calls, and
iframe/frame previews. Existing percent escapes, query separators and browser
fragments are preserved. External URLs are unchanged.

Rebuild the existing solution, restart the application, and open the search
result again. There is no database or Lucene-index change in this fix.

Validation:
- Reproduced the 400 response using Java's actual HttpServer parser.
- The guarded request returns 200 and retains the Arabic query text.
- Tested link clicks, window.open, iframe navigation, valid percent escapes,
  fragments, external URLs and repeated normalization.
- All non-Graal desktop Java sources compile against bundled dependencies.
- Existing search parity and JavaScript stop-word regression checks still pass.

The Windows native EXE and supplied external web assets could not be run here.
