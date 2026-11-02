package uk.local.wrestlingchronology;

import android.content.Context;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.json.JSONObject;

/** Small shared listings; streams are fetched only for the selected channel. */
final class TvApi {
  static final class ResponseException extends IOException {
    final int status;
    ResponseException(int status) {
      super("TV request failed (" + status + ").");
      this.status = status;
    }
  }
  final CommunityApi identity;
  private final String origin;
  volatile String credential;
  private volatile double anchor = System.currentTimeMillis() / 1000.0;
  private volatile long tick = SystemClock.elapsedRealtime();

  TvApi(Context context) {
    identity = new CommunityApi(context);
    origin = BuildConfig.COMMUNITY_BASE_URL + "/tv";
  }

  double now() {
    return anchor + (SystemClock.elapsedRealtime() - tick) / 1000.0;
  }

  JSONObject request(String path, JSONObject body) throws Exception {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
    credential = identity.tvCredential();
    HttpURLConnection connection =
        (HttpURLConnection) new URL(origin + path).openConnection();
    connection.setConnectTimeout(7000);
    connection.setReadTimeout(10000);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty("Authorization", "Bearer " + credential);
    connection.setRequestProperty("Accept-Encoding", "gzip");
    connection.setRequestProperty("Accept", "application/json");
    try {
      if (body != null) {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = connection.getOutputStream()) {
          out.write(bytes);
        }
      }
      int status = connection.getResponseCode();
      if (status != 200) throw new ResponseException(status);
      InputStream stream = connection.getInputStream();
      if ("gzip".equalsIgnoreCase(connection.getContentEncoding()))
        stream = new GZIPInputStream(stream);
      try (InputStream in = stream;
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] bytes = new byte[8192];
        int count;
        while ((count = in.read(bytes)) != -1) {
          if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
          if (out.size() + count > 5 * 1024 * 1024)
            throw new IOException("TV listing exceeds its limit");
          out.write(bytes, 0, count);
        }
        JSONObject result = new JSONObject(out.toString("UTF-8"));
        if (path.equals("/api/time")) {
          anchor = result.getDouble("now");
          tick = SystemClock.elapsedRealtime();
        }
        return result;
      }
    } finally {
      connection.disconnect();
    }
  }

  String mediaUrl(String path) throws IOException {
    if (path.matches("/(?:tv/)?archive-start/[a-f0-9]{24}"))
      return origin + path.replaceFirst("^/tv", "");
    if (path.startsWith("/tv/")) return BuildConfig.COMMUNITY_BASE_URL + path;
    if (path.startsWith("https://")) return path;
    throw new IOException("Unsupported TV stream URL");
  }
}
