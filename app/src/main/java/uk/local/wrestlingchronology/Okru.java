package uk.local.wrestlingchronology;

import android.text.Html;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.*;
import org.json.*;

/** Public OK.ru metadata is resolved on the viewing device; no account is distributed. */
final class Okru {
  static volatile Map<String, String> owners = Collections.emptyMap();
  static final int MAX_METADATA = 4 * 1024 * 1024;

  static void configure(JSONObject config) throws Exception {
    Map<String, String> listed = new HashMap<>();
    JSONObject section = config.optJSONObject("okru");
    if (section != null && section.optBoolean("enabled", true)) {
      JSONArray videos = section.optJSONArray("videos");
      if (videos != null) for (int i = 0; i < videos.length(); i++) {
        JSONObject video = videos.getJSONObject(i);
        String id = video.getString("id"), owner = video.getString("owner");
        if (!id.matches("\\d{5,20}") || !owner.matches("\\d{5,20}"))
          throw new JSONException("Invalid OK.ru identity");
        if (video.optBoolean("enabled", true)) listed.put(id, owner);
      }
    }
    owners = Collections.unmodifiableMap(listed);
  }

  static boolean mediaUrl(String value) {
    try {
      URI uri = new URI(value);
      String host = uri.getHost();
      return "https".equals(uri.getScheme()) && uri.getUserInfo() == null
          && (uri.getPort() == -1 || uri.getPort() == 443) && host != null
          && (host.endsWith(".okcdn.ru") || host.endsWith(".vkuser.net") || host.endsWith(".mycdn.me"));
    } catch (Exception ignored) { return false; }
  }

  static String page(String id) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL("https://ok.ru/video/" + id).openConnection();
    connection.setConnectTimeout(12000); connection.setReadTimeout(18000);
    connection.setRequestProperty("User-Agent", Resolver.USER_AGENT);
    connection.setRequestProperty("Referer", "https://ok.ru/");
    try {
      if (connection.getResponseCode() != 200) throw new IOException("OK.ru video unavailable");
      try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[16384]; int count;
        while ((count = in.read(buffer)) != -1) {
          if (out.size() + count > MAX_METADATA) throw new IOException("OK.ru metadata is too large");
          out.write(buffer, 0, count);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
      }
    } finally { connection.disconnect(); }
  }

  static String progressive(JSONObject metadata, int quality) throws JSONException {
    JSONArray videos = metadata.optJSONArray("videos");
    String selected = null;
    int best = 0;
    if (videos == null) return null;
    for (int i = 0; i < videos.length(); i++) {
      JSONObject video = videos.getJSONObject(i);
      String name = video.optString("name");
      int height = name.equals("mobile") ? 144 : name.equals("lowest") ? 240
          : name.equals("low") ? 360 : name.equals("sd") ? 480
          : name.equals("hd") ? 720 : name.equals("full") ? 1080 : 0;
      if (height > best && height <= quality && mediaUrl(video.optString("url"))) {
        best = height; selected = video.getString("url");
      }
    }
    return selected;
  }

  /** Race small range reads, not whole recordings; use only the provider's listed hosts. */
  static String responsiveUrl(String original, JSONArray hosts) throws Exception {
    LinkedHashSet<String> urls = new LinkedHashSet<>();
    URI uri = new URI(original);
    if (hosts != null) for (int i = 0; i < Math.min(2, hosts.length()); i++) {
      String host = hosts.optString(i);
      if (!host.matches("[A-Za-z0-9.-]+") || !mediaUrl("https://" + host + "/")) continue;
      urls.add(original.replace("https://" + uri.getRawAuthority() + "/", "https://" + host + "/"));
    }
    urls.add(original);
    ExecutorService pool = Executors.newFixedThreadPool(urls.size());
    CompletionService<String> finished = new ExecutorCompletionService<>(pool);
    for (String url : urls) finished.submit(() -> {
      HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
      connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
      connection.setRequestProperty("User-Agent", Resolver.USER_AGENT);
      connection.setRequestProperty("Referer", "https://ok.ru/");
      connection.setRequestProperty("Range", "bytes=0-262143");
      try {
        if (connection.getResponseCode() != 206) return null;
        try (InputStream input = connection.getInputStream()) {
          byte[] buffer = new byte[16384]; int total = 0, count;
          while (total < 262144 && (count = input.read(buffer, 0, Math.min(buffer.length, 262144-total))) > 0) total += count;
          return total == 262144 ? url : null;
        }
      } finally { connection.disconnect(); }
    });
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    try {
      for (int i = 0; i < urls.size(); i++) {
        long remaining = deadline-System.nanoTime();
        if (remaining <= 0) break;
        Future<String> result = finished.poll(remaining, TimeUnit.NANOSECONDS);
        if (result == null) break;
        try { String url = result.get(); if (url != null) return url; }
        catch (ExecutionException ignored) { }
      }
      return null;
    } finally { pool.shutdownNow(); }
  }

  static Resolver.Resolved resolve(JSONObject source, int quality) throws Exception {
    String id = source.getString("videoId"), owner = source.getString("owner");
    if (!owner.equals(owners.get(id))) throw new IOException("OK.ru source is not configured");
    String html = page(id);
    Matcher author = Pattern.compile("<a\\b[^>]*movie_author_link[^>]*>|<a\\b[^>]*href=[\"']/profile/" + owner + "/[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
    boolean verified = false;
    while (author.find()) if (author.group().contains("movie_author_link") && author.group().contains("/profile/" + owner + "/")) verified = true;
    if (!verified) throw new IOException("OK.ru uploader could not be verified");
    Matcher player = Pattern.compile("data-options=([\"'])(.*?)\\1", Pattern.DOTALL).matcher(html);
    while (player.find()) {
      JSONObject options;
      try { options = new JSONObject(Html.fromHtml(player.group(2), Html.FROM_HTML_MODE_LEGACY).toString()); }
      catch (JSONException ignored) { continue; }
      JSONObject flash = options.optJSONObject("flashvars");
      if (flash == null) continue;
      Object value = flash.opt("metadata");
      JSONObject metadata = value instanceof JSONObject ? (JSONObject) value : value instanceof String ? new JSONObject((String) value) : null;
      if (metadata == null || !"UPLOADED_ODKL".equals(metadata.optString("provider"))) continue;
      JSONObject movie = metadata.optJSONObject("movie");
      if (movie == null || !id.equals(movie.optString("movieId", movie.optString("id")))) continue;
      String mp4 = progressive(metadata, quality);
      String selected = mp4 == null ? null : responsiveUrl(mp4, metadata.optJSONArray("failoverHosts"));
      String url = metadata.optString("hlsManifestUrl", metadata.optString("ondemandHls"));
      if (!mediaUrl(url)) throw new IOException("No compatible public OK.ru stream");
      Resolver.Resolved result = new Resolver.Resolved();
      result.provider = "okru"; result.label = "OK.ru"; result.videos.add(url);
      result.hls = true;
      result.originHost = new URI(url).getHost();
      result.deliveryHost = selected == null ? result.originHost : new URI(selected).getHost();
      return result;
    }
    throw new IOException("OK.ru video is unavailable or requires provider login");
  }
}
