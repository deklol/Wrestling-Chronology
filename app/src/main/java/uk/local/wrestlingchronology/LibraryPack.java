package uk.local.wrestlingchronology;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.json.*;

/** Data-only updates. Missing rows never mean delete; removals must be explicit. */
final class LibraryPack {
  static final int MAX_BYTES = 48 * 1024 * 1024;

  static String sha256(byte[] bytes) throws Exception {
    StringBuilder result = new StringBuilder();
    for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
      result.append(String.format(Locale.ROOT, "%02x", value & 255));
    return result.toString();
  }

  static boolean https(String value) {
    try {
      URI uri = new URI(value);
      return "https".equals(uri.getScheme())
          && uri.getHost() != null
          && uri.getUserInfo() == null
          && uri.getFragment() == null;
    } catch (Exception ignored) {
      return false;
    }
  }

  static JSONObject verified(byte[] bytes, int size, String hash, Company company)
      throws Exception {
    if (bytes == null
        || size < 1
        || size > MAX_BYTES
        || bytes.length != size
        || !sha256(bytes).equals(hash))
      throw new java.io.IOException("Source update checksum mismatch; current library kept");
    JSONObject pack = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    validate(pack, company);
    return pack;
  }

  static void validate(JSONObject pack, Company company) throws Exception {
    if (pack.getInt("schemaVersion") != 1
        || !company.id.equals(pack.getString("company"))
        || pack.getLong("revision") < 1) throw new JSONException("Wrong library or format");
    JSONObject config = pack.getJSONObject("sources");
    if (config.getInt("schemaVersion") != 1) throw new JSONException("Unknown source format");
    Set<String> archive = new HashSet<>(), videos = new HashSet<>(), daily = new HashSet<>();
    JSONObject dailymotion = config.optJSONObject("dailymotion");
    if (dailymotion != null) {
      JSONArray listed = dailymotion.getJSONArray("videos");
      for (int i = 0; i < listed.length(); i++) {
        JSONObject value = listed.getJSONObject(i);
        if (!value.getString("id").matches("[A-Za-z0-9]{5,15}")
            || !value.getString("owner").matches("[A-Za-z0-9_-]{1,80}"))
          throw new JSONException("Invalid Dailymotion identity");
        daily.add(value.getString("id") + ":" + value.getString("owner"));
      }
    }
    JSONArray collections = config.getJSONObject("archive").getJSONArray("collections");
    for (int i = 0; i < collections.length(); i++) {
      String id = collections.getJSONObject(i).getString("id");
      if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}") || !archive.add(id))
        throw new JSONException("Invalid or duplicate Archive item");
    }
    JSONObject youtube = config.getJSONObject("youtube");
    JSONArray direct = youtube.optJSONArray("videos");
    if (direct != null)
      for (int i = 0; i < direct.length(); i++) {
        JSONObject item = direct.getJSONObject(i);
        DirectYoutube.validate(item);
        if (!videos.add(item.getString("id") + ":" + item.getString("channelId")))
          throw new JSONException("Duplicate YouTube identity");
      }
    Set<String> ids = new HashSet<>();
    JSONArray rows = pack.getJSONObject("catalogue").getJSONArray("episodes");
    if (rows.length() == 0 || rows.length() > 20000)
      throw new JSONException("Invalid library size");
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      String date = row.getString("date");
      if (!Patterns.get("\\d{4}-\\d{2}-\\d{2}").matcher(date).matches()
          || !LocalDate.of(Integer.parseInt(date.substring(0,4)), Integer.parseInt(date.substring(5,7)), Integer.parseInt(date.substring(8,10))).toString().equals(date)
          || date.compareTo(company.firstDate) < 0
          || date.compareTo(company.lastDate) > 0
          || row.getInt("year") != Integer.parseInt(date.substring(0, 4))
          || !ids.add(row.getString("id"))
          || row.getString("title").isEmpty()
          || row.getString("show").isEmpty()) throw new JSONException("Invalid broadcast identity");
      JSONArray sources = row.getJSONArray("sources");
      if (sources.length() == 0 || sources.length() > 100)
        throw new JSONException("Missing sources");
      for (int j = 0; j < sources.length(); j++) {
        JSONObject source = sources.getJSONObject(j);
        if (source.getString("type").equals("archive")) {
          JSONArray parts = source.optJSONArray("urls");
          if (parts == null) archiveUrl(source.getString("url"), archive);
          else {
            if (parts.length() < 2 || parts.length() > 30)
              throw new JSONException("Invalid part set");
            for (int k = 0; k < parts.length(); k++) archiveUrl(parts.getString(k), archive);
          }
        } else if (source.getString("type").equals("youtube")) {
          if (!source.getString("videoId").matches("[A-Za-z0-9_-]{11}"))
            throw new JSONException("Invalid video ID");
          boolean official = source.optString("channelId").equals(youtube.getString("channelId"));
          if (!official
              && !videos.contains(
                  source.getString("videoId") + ":" + source.getString("channelId")))
            throw new JSONException("Unlisted uploader");
        } else if (source.getString("type").equals("dailymotion")) {
          JSONArray parts = source.optJSONArray("videoIds");
          if (parts == null) parts = new JSONArray().put(source.getString("videoId"));
          if (parts.length() < 1 || parts.length() > 30)
            throw new JSONException("Invalid Dailymotion part set");
          for (int k = 0; k < parts.length(); k++)
            if (!daily.contains(parts.getString(k) + ":" + source.getString("owner")))
              throw new JSONException("Unlisted Dailymotion source");
        } else throw new JSONException("Unsupported provider");
      }
    }
  }

  private static void archiveUrl(String value, Set<String> items) throws Exception {
    URI uri = new URI(value);
    String path = uri.getPath();
    String item = "";
    if (path != null && path.startsWith("/download/")) {
      int end = path.indexOf('/', 10);
      item = end < 0 ? path.substring(10) : path.substring(10, end);
    }
    if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getFragment() != null
        || !"archive.org".equals(uri.getHost()) || uri.getPort() != -1 || !items.contains(item))
      throw new JSONException("Source is outside its configured Archive item");
  }

  static JSONObject merge(JSONObject previous, JSONObject incoming) throws Exception {
    JSONObject result = shallow(incoming);
    JSONObject configCopy = shallow(incoming.getJSONObject("sources"));
    for (String field : new String[] {"archive", "youtube", "dailymotion"})
      if (configCopy.has(field)) configCopy.put(field, shallow(configCopy.getJSONObject(field)));
    result.put("sources", configCopy);
    result.put("catalogue", shallow(incoming.getJSONObject("catalogue")));
    JSONObject catalogue = result.getJSONObject("catalogue");
    LinkedHashMap<String, JSONObject> rows = new LinkedHashMap<>();
    if (previous != null) {
      JSONArray old = previous.getJSONObject("catalogue").getJSONArray("episodes");
      for (int i = 0; i < old.length(); i++)
        rows.put(old.getJSONObject(i).getString("date") + ":" + old.getJSONObject(i).getString("show"), old.getJSONObject(i));
    }
    JSONArray fresh = catalogue.getJSONArray("episodes");
    long now = System.currentTimeMillis();
    for (int i = 0; i < fresh.length(); i++) {
      JSONObject row = shallow(fresh.getJSONObject(i));
      String identity = row.getString("date") + ":" + row.getString("show");
      JSONObject old = rows.get(identity);
      if (old != null) {
        row.put("id", old.getString("id"));
        row.put("discoveredAt", old.optLong("discoveredAt"));
        LinkedHashMap<String, JSONObject> sources = new LinkedHashMap<>();
        for (JSONObject value : list(old.getJSONArray("sources")))
          sources.put(Catalog.sourceKey(value), value);
        for (JSONObject value : list(row.getJSONArray("sources")))
          sources.put(Catalog.sourceKey(value), value);
        row.put("sources", new JSONArray(sources.values()));
      } else row.put("discoveredAt", now);
      rows.put(identity, row);
    }
    Set<String> retired = new HashSet<>();
    JSONArray removals = incoming.optJSONArray("retiredSources");
    if (removals != null)
      for (int i = 0; i < removals.length(); i++) retired.add(removals.getString(i));
    JSONArray accepted = new JSONArray();
    for (JSONObject row : rows.values()) {
      JSONObject copy = shallow(row);
      JSONArray sources = new JSONArray();
      for (JSONObject source : list(copy.getJSONArray("sources")))
        if (!retired.contains(Catalog.sourceKey(source))) sources.put(source);
      if (sources.length() > 0) {
        copy.put("sources", sources);
        accepted.put(copy);
      }
    }
    catalogue.put("episodes", accepted);
    if (previous != null) {
      JSONObject config = result.getJSONObject("sources"), old = previous.getJSONObject("sources");
      mergeConfig(config.getJSONObject("archive"), old.getJSONObject("archive"), "collections");
      mergeConfig(config.getJSONObject("youtube"), old.getJSONObject("youtube"), "videos");
      mergeConfig(config.getJSONObject("youtube"), old.getJSONObject("youtube"), "playlists");
      if (old.has("dailymotion")) {
        if (!config.has("dailymotion"))
          config.put("dailymotion", new JSONObject().put("enabled", true));
        mergeConfig(
            config.getJSONObject("dailymotion"), old.getJSONObject("dailymotion"), "videos");
      }
    }
    return result;
  }

  // Copy containers only. Source metadata is read-only during a merge; retaining
  // its strings avoids several full-library JSON serializations on a 128 MB TV.
  private static JSONObject shallow(JSONObject value) throws Exception {
    JSONObject copy = new JSONObject();
    for (Iterator<String> keys = value.keys(); keys.hasNext();) {
      String key = keys.next(); copy.put(key, value.get(key));
    }
    return copy;
  }

  private static void mergeConfig(JSONObject current, JSONObject old, String field)
      throws Exception {
    LinkedHashMap<String, JSONObject> all = new LinkedHashMap<>();
    JSONArray values = old.optJSONArray(field);
    if (values != null) for (JSONObject value : list(values)) all.put(value.getString("id"), value);
    values = current.optJSONArray(field);
    if (values != null) for (JSONObject value : list(values)) all.put(value.getString("id"), value);
    current.put(field, new JSONArray(all.values()));
  }

  private static List<JSONObject> list(JSONArray values) throws Exception {
    ArrayList<JSONObject> result = new ArrayList<>();
    for (int i = 0; i < values.length(); i++) result.add(values.getJSONObject(i));
    return result;
  }
}
