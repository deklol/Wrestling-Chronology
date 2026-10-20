package uk.local.wrestlingchronology;

import java.io.IOException;
import java.util.*;
import org.json.*;

/** Resolve public, explicitly listed videos; unavailable or restricted media is skipped. */
final class Dailymotion {
  static volatile Map<String, String> owners = Collections.emptyMap();

  static void configure(JSONObject config) throws Exception {
    Map<String, String> allowed = new HashMap<>();
    JSONObject section = config.optJSONObject("dailymotion");
    if (section != null && section.optBoolean("enabled", true)) {
      JSONArray videos = section.optJSONArray("videos");
      if (videos != null)
        for (int i = 0; i < videos.length(); i++) {
          JSONObject video = videos.getJSONObject(i);
          String id = video.getString("id"), owner = video.getString("owner");
          if (!id.matches("[A-Za-z0-9]{5,15}") || !owner.matches("[A-Za-z0-9_-]{1,80}"))
            throw new JSONException("Invalid Dailymotion source");
          if (video.optBoolean("enabled", true)) allowed.put(id, owner);
        }
    }
    owners = Collections.unmodifiableMap(allowed);
  }

  static boolean allowed(JSONObject source) {
    JSONArray parts = source.optJSONArray("videoIds");
    if (parts == null)
      return source.optString("owner").equals(owners.get(source.optString("videoId")));
    for (int i = 0; i < parts.length(); i++)
      if (!source.optString("owner").equals(owners.get(parts.optString(i)))) return false;
    return parts.length() >= 2;
  }

  static Resolver.Resolved resolve(JSONObject source) throws Exception {
    if (!allowed(source)) throw new IOException("Dailymotion source is not configured");
    Resolver.Resolved result = new Resolver.Resolved();
    result.provider = "dailymotion";
    result.label = "Dailymotion";
    JSONArray parts = source.optJSONArray("videoIds");
    if (parts == null) parts = new JSONArray().put(source.getString("videoId"));
    for (int i = 0; i < parts.length(); i++) {
      String id = parts.getString(i);
      JSONObject metadata =
          new JSONObject(
              CatalogSync.get("https://www.dailymotion.com/player/metadata/video/" + id));
      if (metadata.optJSONObject("error") != null || metadata.optJSONObject("qualities") == null)
        throw new IOException("Dailymotion video unavailable");
      if (!source.getString("owner").equals(metadata.getJSONObject("owner").getString("username")))
        throw new IOException("Dailymotion uploader changed");
      JSONArray automatic = metadata.getJSONObject("qualities").optJSONArray("auto");
      String selected = null;
      if (automatic != null)
        for (int j = 0; j < automatic.length(); j++) {
          JSONObject stream = automatic.getJSONObject(j);
          if (stream.optString("type").toLowerCase(Locale.ROOT).contains("mpegurl")
              && LibraryPack.https(stream.optString("url"))) selected = stream.getString("url");
        }
      if (selected == null) throw new IOException("No public compatible Dailymotion stream");
      result.videos.add(selected);
    }
    return result;
  }
}
