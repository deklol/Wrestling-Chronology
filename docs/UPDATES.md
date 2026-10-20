# Source updates without APK updates

The default feed is:

`https://raw.githubusercontent.com/deklol/Wrestling-Chronology/main/catalogue/manifest.json`

The feed is hosted in this public GitHub repository; no PC needs to remain on. The app retains its bundled or last known good library if the feed is unavailable.

The app first requests a small HTTPS manifest. ETag checks avoid downloading unchanged manifests. Each company has a separate pack with its byte count, SHA-256 and revision. Only the selected company's changed pack downloads. Normal checks are spaced six hours apart; Settings also provides manual refresh.

A downloaded pack is checked for size, hash, company, dates, duplicate IDs, configured sources and allowed provider URLs. It merges with the last known good library and is written atomically. Viewing history is stored separately. Missing rows or source configuration entries never implicitly delete existing recordings. A failed, empty, corrupt or older pack keeps the working library.

Intentional removals use `retiredSources`, containing exact source keys. Do not remove a collection just because one request failed. Set a configured entry's `enabled` flag to false for a deliberate provider or collection disable. Review removals with care; watch history remains keyed to stable broadcast IDs.

The included GitHub Action runs daily and can be started manually. It refreshes public metadata, reuses conditional Archive responses, records per-source failures and validates the result before committing. A broken source is skipped while other sources continue. Frozen curated collections are retained. Scheduled runs on inactive public repositories can be paused by GitHub; manual runs remain available.

The manifest hash checks integrity, not an independent signature. Trust comes from HTTPS and the repository owner. A custom HTTPS feed is configurable in Settings; it receives the same validation and cannot supply executable code.

App updates are separate: GitHub releases carry the APK and require the normal Fire TV installer confirmation. Users can skip a version and check manually in Settings.
