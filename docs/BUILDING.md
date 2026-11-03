# Build

Install JDK 17 and Android SDK Platform 35. Create `local.properties` containing your SDK location, or set `ANDROID_HOME`.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python scripts/test_library.py
python scripts/check_catalogue.py
```

On Windows use `gradlew.bat`. The first build downloads pinned dependencies. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The project uses Java, Android views, RecyclerView, Media3, NewPipeExtractor and embedded Chromium networking. Dailymotion uses the Chromium transport with the same Media3 player and controls. Playback buffers in memory; thumbnails and JSON metadata are cached on disk.

## Release signing

Set these environment variables before `./gradlew :app:assembleRelease`:

```
WRESTLING_KEYSTORE=/private/path/release.keystore
WRESTLING_STORE_PASSWORD=your-store-password
WRESTLING_KEY_ALIAS=your-key-alias
WRESTLING_KEY_PASSWORD=your-key-password
```

Keep the signing key private and use the same key for every update. Without these variables, the release output is unsigned. Published APKs retain the original signing key so existing installations can update in place. A fork signed with a different key cannot replace them without uninstalling first.

For an app update, increment both version fields in `app/build.gradle`. Publish the signed APK as `Wrestling-Chronology.apk` in a GitHub release. The app checks `deklol/Wrestling-Chronology`; a fork must change the repository URL in `Updater.java` and the source feed in `RemoteLibrary.java`.

Private keys, local SDK paths, build outputs and metadata caches are excluded from the public source bundle.

Hosted community features and the shared TV guide require the operator-provided
`WC_COMMUNITY_BASE_URL` and `WC_COMMUNITY_APP_KEY` build environment variables.
Set `WC_COLLECTION_DELIVERY=true` only when that API supports authenticated
collection playback. Without hosted configuration, the app uses direct provider
playback for company libraries; shared community features and TV are unavailable.
The service configuration and signing secrets are not part of this repository.
