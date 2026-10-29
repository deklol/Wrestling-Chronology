package uk.local.wrestlingchronology;

import android.content.Context;
import java.util.*;
import org.json.*;

/** The repository-root sources.json is copied into assets by Gradle. */
final class SourceConfig {
  static volatile int collectionsPerScan = 12;
  static volatile String channelId = "UCbGG2hv4cWmIrBAIhGiCdSg";

  static void load(Context context, JSONObject catalogue) throws Exception {
    apply(
        catalogue,
        new JSONObject(
            Catalog.read(context.getAssets().open("sources/" + Company.current.id + ".json"))));
  }

  static void apply(JSONObject catalogue, JSONObject config) throws Exception {
    Dailymotion.configure(config);
    if (config.getInt("schemaVersion") != 1)
      throw new JSONException("Unsupported sources.json version");
    JSONObject youtube = config.getJSONObject("youtube"), archive = config.getJSONObject("archive");
    channelId = youtube.getString("channelId");
    if (!channelId.matches("UC[A-Za-z0-9_-]{22}"))
      throw new JSONException("Invalid YouTube channel ID");
    collectionsPerScan = Math.max(1, Math.min(50, archive.optInt("collectionsPerScan", 12)));
    JSONArray playlists = new JSONArray(), identifiers = new JSONArray(), videos = new JSONArray();
    Map<String, String> directChannels = new HashMap<>();
    Set<String> listedVideos = new HashSet<>();
    JSONArray direct = youtube.optJSONArray("videos");
    if (direct != null)
      for (int i = 0; i < direct.length(); i++) {
        JSONObject item = direct.getJSONObject(i);
        DirectYoutube.validate(item);
        if (!listedVideos.add(item.getString("id")))
          throw new JSONException("Duplicate YouTube video ID");
        if (youtube.optBoolean("enabled", true) && item.optBoolean("enabled", true)) {
          videos.put(item);
          directChannels.put(item.getString("id"), item.getString("channelId"));
        }
      }
    DirectYoutube.channels = Collections.unmodifiableMap(directChannels);
    JSONObject hints = new JSONObject(), labels = new JSONObject(), dateOrders = new JSONObject();
    Map<String, String> playlistForShow = new HashMap<>();
    Set<String> activePlaylists = new HashSet<>(), activeItems = new HashSet<>();
    JSONArray configured = youtube.getJSONArray("playlists");
    if (youtube.optBoolean("enabled", true))
      for (int i = 0; i < configured.length(); i++) {
        JSONObject item = configured.getJSONObject(i);
        if (!item.optBoolean("enabled", true)) continue;
        String id = item.getString("id");
        if (!id.matches("[A-Za-z0-9_-]{10,80}"))
          throw new JSONException("Invalid YouTube playlist ID");
        playlists.put(item);
        activePlaylists.add(id);
        playlistForShow.put(item.optString("show"), id);
      }
    configured = archive.getJSONArray("collections");
    if (archive.optBoolean("enabled", true))
      for (int i = 0; i < configured.length(); i++) {
        JSONObject item = configured.getJSONObject(i);
        if (!item.optBoolean("enabled", true)) continue;
        String id = item.getString("id");
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}"))
          throw new JSONException("Invalid Archive collection ID");
        identifiers.put(id);
        activeItems.add(id);
        labels.put(id, item.optString("name", id));
        if (item.has("showHint")) hints.put(id, item.getString("showHint"));
        if (item.has("dateOrder")) {
          String order = item.getString("dateOrder");
          if (!order.equals("MDY") && !order.equals("DMY"))
            throw new JSONException("Invalid Archive dateOrder");
          dateOrders.put(id, order);
        }
      }
    JSONArray rows = catalogue.getJSONArray("episodes");
    for (int i = rows.length() - 1; i >= 0; i--) {
      JSONObject row = rows.getJSONObject(i);
      JSONArray sources = row.getJSONArray("sources");
      for (int j = sources.length() - 1; j >= 0; j--) {
        JSONObject source = sources.getJSONObject(j);
        boolean keep = true;
        if (source.optString("type").equals("youtube")) {
          String video = source.optString("videoId");
          if (source.optBoolean("direct") || listedVideos.contains(video)) {
            keep = DirectYoutube.allowed(video, source.optString("channelId"));
            if (keep) source.put("direct", true);
          } else {
            String playlist =
                source.optString(
                    "playlistId", playlistForShow.getOrDefault(row.optString("show"), ""));
            keep =
                activePlaylists.contains(playlist)
                    && channelId.equals(source.optString("channelId"));
            if (keep) source.put("playlistId", playlist);
          }
        } else if (source.optString("type").equals("archive")) {
          JSONArray parts = source.optJSONArray("urls");
          if (parts == null)
            keep = activeItems.contains(ArchiveSync.collection(source.optString("url")));
          else
            for (int k = 0; k < parts.length(); k++)
              keep &= activeItems.contains(ArchiveSync.collection(parts.getString(k)));
        } else if (source.optString("type").equals("dailymotion")) {
          keep = Dailymotion.allowed(source);
        }
        if (!keep) sources.remove(j);
      }
      if (sources.length() == 0) rows.remove(i);
    }
    catalogue
        .put("playlists", playlists)
        .put("youtubeVideos", videos)
        .put("archiveItems", identifiers)
        .put("archiveHints", hints)
        .put("archiveDateOrders", dateOrders)
        .put("archiveLabels", labels);
  }
}
