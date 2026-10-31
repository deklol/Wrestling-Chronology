# Ring of Honor

ROH has its own library, viewing history, official logo and red/black theme. Its
initial 482 broadcasts include early events, High Impact TV, HDNet, ROH TV,
HonorClub and specials. Available programmes can be filtered inside each year.
Coverage is incomplete; metadata screening is not end-to-end playback validation.

## Source maintenance

Edit `sources/roh.json`. Archive `overrides` map exact filenames to broadcast
dates, categories and titles; `part` joins all discs of a split event. `mappedOnly`
keeps unexpected files, media scrums and extras out. Do not use upload dates as
broadcast dates. In particular, HonorClub #82 and #88 have explicit corrections
for erroneous years in the original metadata.

The scheduled GitHub catalogue job includes ROH automatically. It checks the
configured Archive collections, discovers recent standalone weeklies using the
queries in `archive.discovery`, and scans recent official YouTube uploads. The
discovery queries are bounded to 100 recent results each; this is an incremental
daily check, not an exhaustive historical rescan. Newly discovered collections
are retained in the published pack on subsequent runs. Failed items are reported
individually and do not remove working recordings.

Official YouTube discoveries require an unambiguous broadcast date and verified
channel owner. Undated events and unfamiliar titles need a reviewed mapping;
they are never guessed from upload dates or episode-number arithmetic.

Run `python scripts/publish_catalogue.py --refresh`, then
`python scripts/check_catalogue.py`. The manifest carries ROH's revision, byte
length and SHA-256 hash. The app's existing refresh button fetches that changed
pack, so later source additions do not require another APK.

## Publishing

Publish the Android build and the repository changes together: `sources/roh.json`,
the bundled ROH library, `catalogue/roh.json`, `catalogue/manifest.json`, the event
reference and updater scripts. The new company requires the initial 0.1.5 APK.
Old APKs continue to use their own companies and ignore the extra manifest entry.

The website is a separate application. Its company allowlist and catalogue must
also include ROH before shared ROH comments/likes/views can be served. Do not copy
Android files over the website frontend or overwrite its databases.

Logo source: the official Ring of Honor website footer, retrieved 30 October 2026.
The PNG is a static rendering of the supplied GIF. ROH retains its brand rights.
