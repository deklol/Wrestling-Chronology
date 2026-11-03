# Third-party material

## Software

| Component | Version | Licence / upstream |
|---|---|---|
| AndroidX Media3 | 1.8.0 | Apache-2.0; https://github.com/androidx/media |
| Embedded Chromium Cronet | 119.6045.31 | BSD-style and third-party notices; https://chromium.googlesource.com/chromium/src/ |
| AndroidX RecyclerView | 1.3.2 | Apache-2.0; https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX annotations and related AndroidX dependencies | As resolved by Gradle | Apache-2.0; AndroidX upstream |
| NewPipeExtractor | v0.26.5 | GPL-3.0-or-later; https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5 |
| Rhino, used by NewPipeExtractor | 1.8.1 | MPL-2.0; https://github.com/mozilla/rhino |
| jsoup, used by NewPipeExtractor | 1.22.2 | MIT; https://github.com/jhy/jsoup |
| Protocol Buffers Java Lite, used by NewPipeExtractor | 4.35.1 | BSD-3-Clause; https://github.com/protocolbuffers/protobuf |
| nanojson fork, used by NewPipeExtractor | Declared by the pinned extractor | Apache-2.0; https://github.com/TeamNewPipe/nanojson |
| JDK desugaring libraries | 2.1.5 | Apache-2.0 with upstream notices; https://github.com/google/desugar_jdk_libs |
| Gradle wrapper/build tooling | 8.7 | Apache-2.0; https://github.com/gradle/gradle |
| JUnit, tests only | 4.13.2 | EPL-1.0; https://github.com/junit-team/junit4 |
| JSON-java, tests only | 20250517 | Public domain; https://github.com/stleary/JSON-java |

Dependencies retain their own licences and notices. Refer to their published source artifacts and POM metadata for transitive dependencies. Apache-2.0 text and relevant upstream source material are included under `third_party/`.

## Catalogue and artwork

Catalogue information comes from public metadata provided by the configured sources. Event posters and some descriptions reference TMDB; this project is not endorsed or certified by TMDB. Source hosts and rights holders retain rights in their content.

Company logos, opening/hero artwork, episode thumbnails and event posters are **not relicensed by the software's GPL licence**. WWE, WCW, ECW and TNA names and marks belong to their respective owners. The supplied Nitro artwork retains its visible original artist credit to **SONKESH KART**. The collage and company hero images were supplied by the project owner; visible artist credits are retained in the original files. Logo provenance is recorded in `app/src/main/assets/logos/provenance.json`. The WWE Android vector uses the original logo paths without the preview's solid background. Branding/artwork may be replaced independently of the GPL app code.

## Externally hosted media

All media linked by Wrestling Chronology is found on publicly accessible source pages. The app's creator does not upload or host these recordings. Videos remain on their external providers: [YouTube](https://www.youtube.com/), [Internet Archive](https://archive.org/), [Dailymotion](https://www.dailymotion.com/) and [OK.ru](https://ok.ru/).

The sources are public. See the [public source list on Pastebin](https://pastebin.com/8seAfcF0) and the [source links organised by company and provider](docs/media-sources/README.md). The repository lists include configured collections, playlists, channels, uploader pages and individual recording pages. Source availability can change.

| Library | Public source links |
|---|---|
| WCW | [Collections, playlists and recordings](docs/media-sources/wcw.md) |
| WWF / WWE | [Collections, playlists and recordings](docs/media-sources/wwe.md) |
| ECW | [Collections, playlists and recordings](docs/media-sources/ecw.md) |
| Ring of Honor | [Collections, playlists and recordings](docs/media-sources/roh.md) |
| TNA / Impact | [Collections, playlists and recordings](docs/media-sources/tna.md) |
| NJPW | [Collections, playlists and recordings](docs/media-sources/njpw.md) |
| NXT | [Collections, playlists and recordings](docs/media-sources/nxt.md) |
| AEW | [Collections, playlists and recordings](docs/media-sources/aew.md) |
| WCtv additional programmes | [Additional shows, specials and promotions](docs/media-sources/tv.md) |

App/project credit: [digitalm1nd](https://x.com/digitalm1nd).
