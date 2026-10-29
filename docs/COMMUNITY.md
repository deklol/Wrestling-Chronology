# Native website community

The Fire TV app uses the deployed, community-only API at https://wc.dek.cx.
Source discovery, thumbnails and playback continue to use the existing sources.

Set `WC_COMMUNITY_APP_KEY` in the build process environment before running Gradle.
The owner has the grant in `app-community-key.private.json` outside this repository.
Never commit that file, access tokens or server configuration. The generated APK
contains the limited registration grant. It does not contain website credentials.
A build without the setting keeps playback working but community is unavailable.
For CI builds, supply this environment variable from a repository secret.

Each installation generates 32 random bytes once and retains the resulting hex
identity in private preferences across APK updates. Backup is disabled. The shared
grant registers that identity for a renewable, 180-day community-only token.
IP addresses and hardware identifiers do not determine likes or comment ownership.
Clearing app data or uninstalling creates a new identity, unlike updating the APK.
People sharing one installation share its identity; display names are not accounts.

Selecting an episode opens a full watch page: the native player and discussion on
the left, episode information and source controls in the right-hand sidebar.
The full-screen toggle reparents the same player without restarting playback.
The page provides shared counts,
likes, optional display names, comments, replies, pagination and confirmed deletion
of owned comments. Company homes load ranked local episodes and their real counters.
Remote responses are discarded after the panel or company changes. Network work
uses a separate bounded executor so it cannot queue in front of playback resolution.

Views reuse the episode discussion response and count 15 seconds of
actual playback, excluding pause/buffering time, seeks and verification clips.
Source retries and recording parts preserve progress; new episodes reset it.
One transient retry is allowed after five more playing seconds, using the same
opaque ticket. The server's returned total is authoritative.

The owner requested that each separate watch count, even from the same IP/device.
The website agent owns the backend change from the original 30-minute per-identity
suppression. The Android app obtains a fresh ticket for each new watch and reuses
it only for that watch's network retry. Until that server change is deployed, the
original server deduplication still applies. No website files are changed here.

Version name is 0.1.4; version code is 8 so existing 0.1.3 installations can
receive this build. Update the release APK and update.json together when publishing.
