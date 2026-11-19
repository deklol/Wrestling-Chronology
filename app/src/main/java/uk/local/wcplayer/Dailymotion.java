package uk.local.wcplayer;

import java.io.IOException;
import java.util.*;
import org.json.*;

/** Reads metadata for the individual video selected by the user. */
final class Dailymotion {
  static Resolver.Resolved resolve(JSONObject source) throws Exception {
    Resolver.Resolved result = new Resolver.Resolved();
    result.provider = "dailymotion";
    result.label = "Dailymotion";
    JSONArray parts = source.optJSONArray("videoIds");
    if (parts == null) parts = new JSONArray().put(source.getString("videoId"));
    for (int i = 0; i < parts.length(); i++) {
      String id = parts.getString(i);
      if (!id.matches("[A-Za-z0-9]{5,15}")) throw new IOException("Invalid Dailymotion identity");
      JSONObject metadata =
          new JSONObject(
              Resolver.get("https://www.dailymotion.com/player/metadata/video/" + id));
      if (metadata.optJSONObject("error") != null || metadata.optJSONObject("qualities") == null)
        throw new IOException("Dailymotion video unavailable");
      JSONArray automatic = metadata.getJSONObject("qualities").optJSONArray("auto");
      String selected = null;
      if (automatic != null)
        for (int j = 0; j < automatic.length(); j++) {
          JSONObject stream = automatic.getJSONObject(j);
          if (stream.optString("type").toLowerCase(Locale.ROOT).contains("mpegurl")
              && stream.optString("url").startsWith("https://")) selected = stream.getString("url");
        }
      if (selected == null) throw new IOException("No public compatible Dailymotion stream");
      result.videos.add(selected);
    }
    result.hls = true;
    return result;
  }
}
