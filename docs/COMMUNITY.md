# Hosted features

Likes, comments, views and the shared TV guide use an operator-configured API.
Company source libraries and provider playback work independently of it.

Set `WC_COMMUNITY_BASE_URL` and `WC_COMMUNITY_APP_KEY` in the private build
environment to enable hosted features. Never commit their values, registration
grants, access tokens or server configuration. Builds without these settings
can still browse and play the public company libraries.

Each installation creates a random identity and exchanges its registration
grant for a renewable, scoped token. Updating the APK preserves that identity;
uninstalling or clearing app data creates a new one. IP addresses are not used
as comment or like ownership identifiers.

A view is submitted after 15 seconds of actual playback. Pauses, buffering and
seeks do not add playback time. Each watch gets a fresh ticket; retries reuse
the same ticket to avoid double-counting.
