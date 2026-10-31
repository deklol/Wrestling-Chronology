package uk.local.wrestlingchronology;

import android.content.Context;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.*;
import org.json.*;

/** Named individual uploads, with explicit broadcast dates and uploader identities. */
final class DirectYoutube {
  static volatile Map<String, String> channels = Collections.emptyMap();

  static boolean allowed(String video, String channel) {
    return channel.equals(channels.get(video));
  }

  static boolean preshow(JSONObject entry) {
    return "preshow".equals(entry.optString("eventPart"))
        && "PPV".equals(entry.optString("show"))
        && "UCFN4JkGP_bVhAdBsoV9xftA".equals(entry.optString("channelId"));
  }

  static int count(JSONObject data) {
    JSONArray videos = data.optJSONArray("youtubeVideos");
    return videos == null ? 0 : videos.length();
  }

  static String fingerprint(JSONObject entry, JSONObject metadata) throws Exception {
    return MetadataCache.hash(
        "direct-video-v1:"
            + entry.getString("id")
            + "\n"
            + entry.getString("channelId")
            + "\n"
            + entry.getString("date")
            + "\n"
            + entry.getString("show")
            + "\n"
            + entry.getString("title")
            + "\n"
            + entry.getInt("duration")
            + "\n"
            + metadata.getString("title")
            + "\n"
            + metadata.getString("author_url"));
  }

  static void validate(JSONObject entry) throws Exception {
    if (!entry.getString("id").matches("[A-Za-z0-9_-]{11}")
        || !entry.getString("channelId").matches("UC[A-Za-z0-9_-]{22}")
        || !entry.getString("authorUrl").matches("https://www\\.youtube\\.com/@[A-Za-z0-9_.-]+"))
      throw new JSONException("Invalid direct YouTube source identity");
    LocalDate date = LocalDate.parse(entry.getString("date"));
    if (CatalogSync.validDate(date.getYear(), date.getMonthValue(), date.getDayOfMonth()) == null
        || entry.getInt("duration") < (entry.getString("show").equals("PPV") && !preshow(entry) ? 2700 : 1200)
        || entry.getString("title").isEmpty()
        || entry.getString("name").isEmpty())
      throw new JSONException("Invalid direct YouTube broadcast metadata");
  }

  static boolean present(JSONObject data, String id) throws Exception {
    JSONArray rows = data.getJSONArray("episodes");
    for (int i = 0; i < rows.length(); i++) {
      JSONArray sources = rows.getJSONObject(i).getJSONArray("sources");
      for (int j = 0; j < sources.length(); j++)
        if (id.equals(sources.getJSONObject(j).optString("videoId"))) return true;
    }
    return false;
  }

  static int merge(JSONObject data, JSONObject entry, JSONObject metadata) throws Exception {
    validate(entry);
    String author = metadata.getString("author_url").replaceFirst("^http://", "https://");
    if (!author.equalsIgnoreCase(entry.getString("authorUrl")))
      throw new IOException("Video uploader does not match its configured source");
    String title = metadata.getString("title");
    boolean pre = preshow(entry);
    if ((!title.matches("(?is).*\\bFULL (?:EPISODE|EVENT)\\b.*")
            && !(pre && title.matches("(?is).*\\b(?:zero[ ._-]*hour|buy[ ._-]*in|pre[ ._-]*show)\\b.*")))
        || title.matches("(?is).*(?:FULL MATCH|watch party|compilation).*"))
      throw new IOException("Upload is not labelled as a full show or event");
    String when = entry.getString("date"),
        show = entry.getString("show"),
        id = entry.getString("id");
    JSONArray rows = data.getJSONArray("episodes");
    JSONObject selected = null;
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      boolean rowPre = "preshow".equals(row.optString("eventPart"));
      if (when.equals(row.optString("date")) && show.equals(row.optString("show"))
          && pre == rowPre
          && (!show.equals("PPV") || pre || CatalogSync.key(entry.getString("title")).equals(CatalogSync.key(row.optString("title"))))) {
        selected = row;
        break;
      }
    }
    if (selected == null) {
      String name = entry.getString("title");
      selected =
          new JSONObject()
              .put("id", when + ":" + CatalogSync.key(show.equals("PPV") ? name : show))
              .put("date", when)
              .put("year", Integer.parseInt(when.substring(0, 4)))
              .put("show", show)
              .put("title", name)
              .put("category", show.equals("PPV") ? "ppv" : "weekly")
              .put("duration", entry.getInt("duration"))
              .put("description", entry.optString("description"))
              .put("thumbnail", "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg")
              .put("sources", new JSONArray())
              .put("discoveredAt", System.currentTimeMillis());
      if (show.equals("PPV")) selected.put("poster", selected.getString("thumbnail"));
      rows.put(selected);
    }
    if (pre) selected.put("eventPart", "preshow").put("eventOrder", -1);
    JSONArray sources = selected.getJSONArray("sources");
    for (int i = 0; i < sources.length(); i++)
      if (id.equals(sources.getJSONObject(i).optString("videoId"))) return 0;
    sources.put(
        new JSONObject()
            .put("type", "youtube")
            .put("videoId", id)
            .put("channelId", entry.getString("channelId"))
            .put("direct", true)
            .put("title", title)
            .put("duration", entry.getInt("duration"))
            .put("label", "Official " + metadata.optString("author_name", "YouTube")));
    return 1;
  }

  static int refresh(Context context, JSONObject data) throws Exception {
    JSONArray videos = data.optJSONArray("youtubeVideos");
    if (videos == null) return 0;
    JSONObject hashes = data.optJSONObject("videoHashes");
    if (hashes == null) {
      hashes = new JSONObject();
      data.put("videoHashes", hashes);
    }
    int added = 0;
    for (int i = 0; i < videos.length(); i++) {
      CatalogSync.available();
      JSONObject entry = videos.getJSONObject(i);
      String label = "YouTube · " + entry.getString("name"), id = entry.getString("id");
      CatalogSync.progress(data.getJSONArray("playlists").length() + i, CatalogSync.total, label);
      try {
        String url =
            "https://www.youtube.com/oembed?format=json&url="
                + URLEncoder.encode("https://www.youtube.com/watch?v=" + id, "UTF-8");
        JSONObject metadata = new JSONObject(MetadataCache.get(context, url));
        String fingerprint = fingerprint(entry, metadata);
        boolean unchanged = fingerprint.equals(hashes.optString(id)) && present(data, id);
        int found = unchanged ? 0 : merge(data, entry, metadata);
        hashes.put(id, fingerprint);
        added += found;
        CatalogSync.checked(label, found, unchanged);
      } catch (Exception error) {
        CatalogSync.warning(label + " · " + CatalogSync.reason(error));
      }
    }
    return added;
  }
}
