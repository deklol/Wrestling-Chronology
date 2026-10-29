package uk.local.wrestlingchronology;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Fetch a small manifest, then the selected company's changed JSON pack only. */
final class RemoteLibrary {
  static final String DEFAULT_FEED =
      "https://raw.githubusercontent.com/deklol/Wrestling-Chronology/main/catalogue/manifest.json";

  static JSONObject cached(Context context) {
    AtomicFile file = new AtomicFile(Company.cache(context));
    try (InputStream input = file.openRead()) {
      JSONObject pack = new JSONObject(Catalog.read(input));
      org.json.JSONArray rows = pack.getJSONObject("catalogue").getJSONArray("episodes");
      for (int i = rows.length() - 1; i >= 0; i--)
        if (rows.getJSONObject(i).getString("date").compareTo(Company.current.firstDate) < 0) rows.remove(i);
      org.json.JSONArray videos = pack.getJSONObject("sources").getJSONObject("youtube").optJSONArray("videos");
      if (videos != null) for (int i = videos.length() - 1; i >= 0; i--)
        if (videos.getJSONObject(i).optString("date", Company.current.firstDate).compareTo(Company.current.firstDate) < 0) videos.remove(i);
      LibraryPack.validate(pack, Company.current);
      return pack;
    } catch (Exception ignored) {
      return null;
    }
  }

  static JSONObject bundled(Context context) throws Exception {
    return new JSONObject()
        .put("schemaVersion", 1)
        .put("company", Company.current.id)
        .put("revision", 1793232001L)
        .put(
            "catalogue",
            new JSONObject(
                Catalog.read(
                    context.getAssets().open("libraries/" + Company.current.id + ".json"))))
        .put(
            "sources",
            new JSONObject(
                Catalog.read(context.getAssets().open("sources/" + Company.current.id + ".json"))));
  }

  static byte[] download(String url, int limit, String etag, String[] response) throws Exception {
    if (!LibraryPack.https(url)) throw new IOException("Source updates require HTTPS");
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(10000);
    connection.setReadTimeout(15000);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty("User-Agent", "WrestlingChronology/" + BuildConfig.VERSION_NAME);
    if (etag != null && !etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);
    try {
      int code = connection.getResponseCode();
      if (code == 304) return null;
      if (code != 200) throw new IOException("Source feed returned HTTP " + code);
      if (connection.getContentLengthLong() > limit)
        throw new IOException("Source update exceeds size limit");
      if (response != null) response[0] = connection.getHeaderField("ETag");
      try (InputStream input = connection.getInputStream();
          ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
          CatalogSync.available();
          if (output.size() + count > limit)
            throw new IOException("Source update exceeds size limit");
          output.write(buffer, 0, count);
          if (limit > 128 * 1024)
            CatalogSync.progress(
                1,
                3,
                "Downloading "
                    + Company.current.name
                    + " sources · "
                    + output.size() / 1024
                    + " KB");
        }
        return output.toByteArray();
      }
    } finally {
      connection.disconnect();
    }
  }

  static CatalogSync.Result refresh(Context context) throws Exception {
    synchronized (CatalogSync.class) {
      if (CatalogSync.busy) return new CatalogSync.Result(0, 0);
      CatalogSync.busy = true;
    }
    CatalogSync.warnings.clear();
    CatalogSync.scanReport.clear();
    long now = System.currentTimeMillis();
    android.content.SharedPreferences preferences = Catalog.prefs(context);
    preferences.edit().putLong("last_refresh_attempt", now).apply();
    try {
      CatalogSync.available();
      String feed =
          context.getSharedPreferences("application", 0).getString("source_feed", DEFAULT_FEED);
      CatalogSync.progress(0, 3, "Checking GitHub source updates · " + Company.current.name);
      String[] headers = new String[1];
      JSONObject saved = cached(context);
      String oldTag =
          saved != null && preferences.getString("manifest_url", "").equals(feed)
              ? preferences.getString("manifest_etag", "")
              : "";
      byte[] bytes = download(feed, 128 * 1024, oldTag, headers);
      if (bytes == null) {
        CatalogSync.checked("GitHub · " + Company.current.name, 0, true);
        return success(context, now, 0, 0);
      }
      JSONObject manifest = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
      if (manifest.getInt("schemaVersion") != 1)
        throw new IOException("Unsupported source manifest");
      if (!manifest.getJSONObject("companies").has(Company.current.id)) return success(context, now, 0, 0);
      JSONObject entry = manifest.getJSONObject("companies").getJSONObject(Company.current.id);
      String hash = entry.getString("sha256");
      int size = entry.getInt("bytes");
      if (!hash.matches("[a-f0-9]{64}") || size < 1 || size > LibraryPack.MAX_BYTES)
        throw new IOException("Invalid source pack metadata");
      if (saved != null && hash.equals(preferences.getString("source_hash", ""))) {
        remember(preferences, feed, headers[0], hash);
        CatalogSync.checked("GitHub · " + Company.current.name, 0, true);
        return success(context, now, 0, 0);
      }
      CatalogSync.progress(1, 3, "Downloading " + Company.current.name + " source pack");
      bytes = download(entry.getString("url"), LibraryPack.MAX_BYTES, "", null);
      JSONObject incoming = LibraryPack.verified(bytes, size, hash, Company.current);
      JSONObject previous = cached(context);
      JSONObject seed = bundled(context);
      previous = previous == null ? seed : LibraryPack.merge(previous, seed);
      if (incoming.getLong("revision") < previous.getLong("revision"))
        throw new IOException("Older source revision ignored");
      CatalogSync.progress(2, 3, "Checking broadcasts and preserving viewing history");
      JSONObject merged = LibraryPack.merge(previous, incoming);
      LibraryPack.validate(merged, Company.current);
      int oldCount = Catalog.episodes.size();
      // Parse everything before replacing the last known good file.
      JSONObject data = merged.getJSONObject("catalogue");
      SourceConfig.apply(data, merged.getJSONObject("sources"));
      for (int i = 0; i < data.getJSONArray("episodes").length(); i++)
        new Catalog.Episode(data.getJSONArray("episodes").getJSONObject(i));
      AtomicFile file = new AtomicFile(Company.cache(context));
      FileOutputStream stream = null;
      try {
        stream = file.startWrite();
        stream.write(merged.toString().getBytes(StandardCharsets.UTF_8));
        file.finishWrite(stream);
      } catch (Exception error) {
        if (stream != null) file.failWrite(stream);
        throw error;
      }
      Catalog.replace(data);
      int added = Math.max(0, Catalog.episodes.size() - oldCount);
      remember(preferences, feed, headers[0], hash);
      CatalogSync.checked("GitHub · " + Company.current.name, added, false);
      return success(context, now, added, 0);
    } catch (Exception error) {
      CatalogSync.warning(
          "GitHub source update skipped: " + CatalogSync.reason(error) + ". Current library kept.");
      preferences
          .edit()
          .putString("last_scan_report", String.join("\n", CatalogSync.scanReport))
          .putString("last_refresh_error", CatalogSync.reason(error))
          .putBoolean("refresh_deferred", error instanceof CatalogSync.Deferred)
          .apply();
      throw error;
    } finally {
      CatalogSync.busy = false;
    }
  }

  private static void remember(
      android.content.SharedPreferences preferences, String feed, String tag, String hash) {
    preferences
        .edit()
        .putString("manifest_url", feed)
        .putString("manifest_etag", tag == null ? "" : tag)
        .putString("source_hash", hash)
        .apply();
  }

  private static CatalogSync.Result success(Context context, long now, int added, int sources) {
    CatalogSync.progress(3, 3, added == 0 ? "Sources are up to date" : added + " new shows found");
    Catalog.prefs(context)
        .edit()
        .putLong("last_refresh", now)
        .putInt("pending_episodes", added)
        .putInt("pending_sources", sources)
        .putString("last_refresh_error", "")
        .putBoolean("refresh_deferred", false)
        .putLong("refresh_retry_after", 0)
        .putString("last_scan_report", String.join("\n", CatalogSync.scanReport))
        .apply();
    return new CatalogSync.Result(added, sources);
  }
}
