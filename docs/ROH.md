# Ring of Honor sources

The ROH library includes events, High Impact TV, HDNet, ROH TV, HonorClub and
specials. Availability varies by year.

Source configuration is in `sources/roh.json`. Archive `overrides` map exact
filenames to broadcast dates, titles and programmes. The `part` field groups
split recordings, and `mappedOnly` excludes files without a reviewed mapping.
Broadcast dates come from show metadata, not upload dates.

The scheduled source refresh checks configured Archive collections, recent
standalone weeklies and official YouTube uploads. Each Archive discovery query
checks up to 100 recent results. Known recordings are retained when a source
fails. New YouTube entries need a clear broadcast date and a matching uploader;
ambiguous titles need a manual mapping.

To refresh and validate the published packs:

```sh
python scripts/publish_catalogue.py --refresh
python scripts/check_catalogue.py
```

Commit the resulting `catalogue/` changes to publish a source update. The app
downloads the changed ROH pack without requiring a new APK.

[Public ROH source links](media-sources/roh.md)

The logo comes from the official Ring of Honor website. ROH retains its brand
rights.
