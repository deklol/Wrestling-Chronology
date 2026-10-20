# Manage sources

The human-readable files in `sources/` are authoritative. Gradle copies them into the APK automatically. `sources.schema.json` describes their structure.

An Archive collection can be added with:

```json
{
  "id": "your-archive-item",
  "name": "Raw 1999",
  "enabled": true,
  "showHint": "Raw",
  "dateOrder": "MDY",
  "year": 1999
}
```

Use `overrides` keyed by exact filename for cryptic titles, exceptional air dates or collections mixing programmes:

```json
"overrides": {
  "opaque-recording.mp4": {
    "date": "1999-01-04",
    "show": "Raw",
    "title": "Raw"
  }
}
```

Never infer Nitro versus Thunder from weekday. Explicit file programme names override a general collection hint; reviewed overrides take precedence over both. `frozen: true` keeps a curated, numbered collection intact without guessing how later files map to events.

YouTube playlists declare `id`, `name`, `show`, `channelId` and `authorUrl`. Use `show: "Auto"` only for mixed upload feeds. Recent upload feeds inspect three pages per run; dedicated playlists can be scanned fully. Newly discovered videos must have a full-show label, sufficient duration, an in-scope date and the expected uploader. Full matches and compilations are excluded. Ambiguous titles remain skipped for review.

Dailymotion `videos` declares each approved ID and owner. `broadcasts` groups IDs into one dated full show. Existing IDs are resolved at playback time, so expiring stream URLs are never saved in the source pack. New Dailymotion broadcasts currently require adding reviewed IDs to this file; the publisher does not crawl whole Dailymotion profiles automatically.

Archive multipart recordings require a complete consecutive part set. Durations and title filters reject obvious partial recordings, but metadata cannot establish that every recording is uncut. Review questionable additions before publishing.

Episode artwork comes from the corresponding YouTube video or Archive per-file still. Collection covers must not replace episode thumbnails. The company logo is the fallback when episode artwork is unavailable.

After editing:

```sh
python scripts/publish_catalogue.py --refresh
python scripts/check_catalogue.py
```

Review `catalogue/scan-report.json` and the changed JSON, then commit `sources/` and `catalogue/`. No APK build is required for data-only source changes after the feed is live. Parser, playback and UI changes still require an app update.
