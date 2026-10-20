package uk.local.wrestlingchronology;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Conditional metadata requests only. Video bytes never enter this cache. */
final class MetadataCache {
  static String hash(String value) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder result = new StringBuilder();
    for (byte part : digest) result.append(String.format(Locale.ROOT, "%02x", part));
    return result.toString();
  }

  static String get(Context context, String url) throws Exception {
    File folder = new File(context.getCacheDir(), "metadata");
    folder.mkdirs();
    File path = new File(folder, hash(url) + ".json");
    JSONObject cached = null;
    try {
      if (path.isFile()) cached = new JSONObject(Catalog.read(new FileInputStream(path)));
    } catch (Exception ignored) {
    }
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(12000);
    connection.setReadTimeout(20000);
    connection.setRequestProperty("User-Agent", "Mozilla/5.0");
    connection.setRequestProperty("Accept-Language", "en-GB,en;q=0.9");
    if (cached != null) {
      if (!cached.optString("etag").isEmpty())
        connection.setRequestProperty("If-None-Match", cached.getString("etag"));
      if (!cached.optString("modified").isEmpty())
        connection.setRequestProperty("If-Modified-Since", cached.getString("modified"));
    }
    try {
      int status = connection.getResponseCode();
      if (status == 304 && cached != null) {
        android.util.Log.i(
            "WCWCatalog", "METADATA_NOT_MODIFIED host=" + connection.getURL().getHost());
        return cached.getString("body");
      }
      if (status != 200) throw new IOException("Metadata host unavailable (" + status + ")");
      String body = Catalog.read(connection.getInputStream());
      String etag = connection.getHeaderField("ETag"),
          modified = connection.getHeaderField("Last-Modified");
      if ((etag != null || modified != null) && body.length() < 5 * 1024 * 1024) {
        JSONObject entry =
            new JSONObject()
                .put("etag", etag == null ? "" : etag)
                .put("modified", modified == null ? "" : modified)
                .put("body", body);
        AtomicFile file = new AtomicFile(path);
        FileOutputStream out = file.startWrite();
        try {
          out.write(entry.toString().getBytes(StandardCharsets.UTF_8));
          file.finishWrite(out);
        } catch (Exception error) {
          file.failWrite(out);
          throw error;
        }
        File[] files = folder.listFiles();
        long size = 0;
        if (files != null) {
          for (File child : files) size += child.length();
          if (size > 24 * 1024 * 1024) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (File child : files) {
              if (size < 18 * 1024 * 1024) break;
              long bytes = child.length();
              if (child.delete()) size -= bytes;
            }
          }
        }
      }
      return body;
    } finally {
      connection.disconnect();
    }
  }
}
