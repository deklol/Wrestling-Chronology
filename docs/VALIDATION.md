# Validation — 0.1.0 test build

Device: AFTSS Fire TV Stick, Android API 28. Minimum supported API in the APK: 25. An API 25 device has not been tested directly in this session.

Device checks:

- Full-screen opening image, delayed fade to company selector, all four logos.
- Company-specific gradients and the supplied hero artwork.
- Distinct episode stills in WWE, ECW and TNA. Repeated WCW collection covers replaced with per-recording artwork while preserving existing individual images and all 1,950 broadcasts.
- WWE Raw, 16 January 1995, advanced to Royal Rumble, 22 January 1995.
- ECW Hardcore TV, 3 January 1995, rendered through the Archive player.
- TNA Genesis 2005 rendered from official YouTube at 720p.
- Dailymotion Impact, 25 November and 9 December 2015, rendered through native Media3 using embedded Chromium networking.
- All four Lockdown 2014 parts played consecutively; the queue then advanced to Impact, 13 March 2014, on Archive.

The debug autoplay probe clips samples to eight seconds and avoids recording those samples as watched. Normal playback is not clipped. A stream starting successfully is not an end-to-end review of every hosted show.

All 58 Java unit tests and 8 Python source-builder tests passed. Android lint reported zero errors; remaining warnings concern pinned dependency updates, TV landscape orientation, unused legacy assets and untranslated English UI text. Checks cover the bundled libraries, source policies, date and programme identity, explicit multipart completeness, uploader checks, stable IDs, safe additive merges, explicit retirements, hash/size verification and app-release metadata. Source-pack hashes and configured provider IDs are checked independently by the Python validator.

The final single APK passed `apksigner verify` with APK Signature Scheme v2. Its declared minimum is API 25 and it includes `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`. Google Play Services is excluded. Dailymotion playback and saved-position resume were rechecked after that exclusion.

The live GitHub source feed and release endpoint cannot be verified until this new repository is published. Keep that separate from playback success. Provider removals, upstream API changes and slow hosts can still affect individual recordings.
