package uk.local.wrestlingchronology;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Community-only credentials. Never used for source or media requests. */
final class CommunityApi {
  // Separate from source resolution: a slow discussion must not queue ahead of playback.
  static final ExecutorService IO = Executors.newFixedThreadPool(2);
  private static final Object AUTH = new Object();
  private static long registrationBackoff;
  final SharedPreferences prefs;

  CommunityApi(Context context) {
    prefs = context.getApplicationContext().getSharedPreferences("community_private", Context.MODE_PRIVATE);
  }

  static String newIdentity() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    StringBuilder hex = new StringBuilder(64);
    for (byte value : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    return hex.toString();
  }

  private String token(boolean renew, String rejected) throws Exception {
    synchronized (AUTH) {
      String saved = prefs.getString("token", "");
      if (!saved.isEmpty() && prefs.getLong("expires", 0) > System.currentTimeMillis() / 1000 + 86400
          && (!renew || !saved.equals(rejected))) return saved;
      if (android.os.SystemClock.elapsedRealtime() < registrationBackoff)
        throw new IOException("Community is temporarily unavailable. Please try again shortly.");
      if (BuildConfig.COMMUNITY_APP_KEY.isEmpty() || BuildConfig.COMMUNITY_BASE_URL.isEmpty())
        throw new IOException("Community is unavailable in this build.");
      String identity = prefs.getString("installationId", "");
      if (identity.isEmpty()) {
        identity = newIdentity();
        if (!prefs.edit().putString("installationId", identity).commit())
          throw new IOException("Could not save your community identity.");
      }
      try {
        JSONObject response = http("/api/app/register", BuildConfig.COMMUNITY_APP_KEY,
            new JSONObject().put("installationId", identity));
        saved = response.getString("accessToken");
        prefs.edit().putString("token", saved).putLong("expires", response.getLong("expiresAt")).commit();
        return saved;
      } catch (Exception error) {
        registrationBackoff = android.os.SystemClock.elapsedRealtime() + 60000;
        throw error;
      }
    }
  }

  JSONObject request(String path, JSONObject body) throws Exception {
    String auth = token(false, "");
    try { return http(path, auth, body); }
    catch (ApiError error) {
      // Only an explicit authentication rejection is safe to resend, including a post.
      if (error.status == 401 && (error.code.equals("token_expired") || error.code.equals("invalid_token")))
        return http(path, token(true, auth), body);
      throw error;
    }
  }

  JSONObject read(String company, String id, String extra) throws Exception {
    return request("/api/app/community?company=" + encode(company) + "&id=" + encode(id) + extra, null);
  }

  JSONObject write(String company, String id, JSONObject action) throws Exception {
    action.put("company", company).put("id", id);
    return request("/api/app/community", action);
  }

  static String encode(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }

  static final class ApiError extends IOException {
    final int status;
    final String code;
    ApiError(int status, String code, String message) { super(message); this.status = status; this.code = code; }
  }

  private static JSONObject http(String path, String token, JSONObject body) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(BuildConfig.COMMUNITY_BASE_URL + path).openConnection();
    connection.setConnectTimeout(10000);
    connection.setReadTimeout(12000);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty("Authorization", "Bearer " + token);
    connection.setRequestProperty("Accept", "application/json");
    try {
      if (body != null) {
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
      }
      int status = connection.getResponseCode();
      InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      if (stream != null) try (InputStream in = stream) {
        byte[] block = new byte[4096]; int n;
        while ((n = in.read(block)) != -1) {
          if (buffer.size() + n > 256 * 1024) throw new IOException("Community response is too large.");
          buffer.write(block, 0, n);
        }
      }
      JSONObject result;
      try { result = new JSONObject(buffer.toString("UTF-8")); }
      catch (Exception ignored) { result = new JSONObject(); }
      if (status < 200 || status >= 300)
        throw new ApiError(status, result.optString("code"), result.optString("error", "Community is temporarily unavailable. Please try again later."));
      if (result.length() == 0) throw new IOException("Community is temporarily unavailable.");
      return result;
    } finally { connection.disconnect(); }
  }
}
