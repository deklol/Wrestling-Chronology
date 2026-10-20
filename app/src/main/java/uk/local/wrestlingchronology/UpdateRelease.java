package uk.local.wrestlingchronology;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import org.json.*;

/** Release metadata and version policy shared by startup and manual checks. */
final class UpdateRelease {
  final int code;
  final String version, url, hash, notes;

  UpdateRelease(int code, String version, String url, String hash, String notes)
      throws IOException {
    this.code = code;
    this.version = normalize(version);
    this.url = url;
    this.hash = hash.toLowerCase(Locale.ROOT);
    this.notes = notes;
    try {
      URI uri = new URI(url);
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || !this.hash.matches("[a-f0-9]{64}")
          || !this.version.matches("[0-9]+(?:\\.[0-9]+){1,3}"))
        throw new IOException("Invalid release metadata");
    } catch (java.net.URISyntaxException error) {
      throw new IOException("Invalid APK URL", error);
    }
  }

  static String normalize(String version) {
    return version.trim().replaceFirst("^[vV]", "");
  }

  static UpdateRelease manifest(JSONObject data) throws Exception {
    int code = data.getInt("versionCode");
    if (code <= 0) throw new IOException("Invalid version code");
    return new UpdateRelease(
        code,
        data.getString("versionName"),
        data.getString("apkUrl"),
        data.getString("sha256"),
        data.optString("notes", "A new Wrestling Chronology update is available."));
  }

  static UpdateRelease github(JSONObject data) throws Exception {
    if (data.optBoolean("draft") || data.optBoolean("prerelease"))
      throw new IOException("No stable release available");
    JSONArray assets = data.getJSONArray("assets");
    for (int i = 0; i < assets.length(); i++) {
      JSONObject asset = assets.getJSONObject(i);
      if ("Wrestling-Chronology.apk".equals(asset.optString("name"))) {
        String digest = asset.optString("digest");
        if (!digest.startsWith("sha256:")) throw new IOException("Release has no SHA-256 digest");
        return new UpdateRelease(
            -1,
            data.getString("tag_name"),
            asset.getString("browser_download_url"),
            digest.substring(7),
            data.optString("body", "A new Wrestling Chronology update is available."));
      }
    }
    throw new IOException("Release has no Wrestling-Chronology.apk asset");
  }

  boolean newerThan(int installedCode, String installedVersion) {
    if (code > 0) return code > installedCode;
    try {
      String[] next = version.split("\\."), current = normalize(installedVersion).split("\\.");
      for (int i = 0; i < Math.max(next.length, current.length); i++) {
        long a = i < next.length ? Long.parseLong(next[i]) : 0;
        long b = i < current.length ? Long.parseLong(current[i]) : 0;
        if (a != b) return a > b;
      }
    } catch (NumberFormatException ignored) {
    }
    return false;
  }

  boolean shouldOffer(
      int installedCode, String installedVersion, String skipped, boolean automatic) {
    return newerThan(installedCode, installedVersion)
        && (!automatic || !version.equals(normalize(skipped)));
  }
}
