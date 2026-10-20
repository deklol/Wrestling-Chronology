package uk.local.wrestlingchronology;

import java.net.URI;
import java.util.*;
import org.json.*;

/** Repair source placement from explicit file evidence, never from weekday guesses. */
final class ArchiveIdentity {
  static Map<String, String> configuredMoves(JSONObject data) throws Exception {
    Map<String, String> result = new HashMap<>();
    JSONObject configured = data.optJSONObject("historyMigrations");
    if (configured == null) return result;
    Set<String> available = new HashSet<>();
    JSONArray rows = data.getJSONArray("episodes");
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      if (row.getJSONArray("sources").length() > 0) available.add(row.getString("id"));
    }
    for (Iterator<String> keys = configured.keys(); keys.hasNext(); ) {
      String old = keys.next(), corrected = configured.getString(old);
      if (available.contains(corrected)) result.put(old, corrected);
    }
    return result;
  }

  static Map<String, Object> historyChanges(Map<String, ?> saved, Map<String, String> moves) {
    Map<String, Object> changes = new HashMap<>();
    for (Map.Entry<String, String> move : moves.entrySet()) {
      String from = move.getKey(), to = move.getValue();
      // Never overwrite independently recorded progress on the correct episode.
      if (!saved.containsKey("pos:" + to) && !saved.containsKey("watched:" + to)) {
        for (String prefix :
            new String[] {
              "pos:", "watched:", "seen:", "length:", "part:", "provider:", "choice:"
            }) {
          if (saved.containsKey(prefix + from)) changes.put(prefix + to, saved.get(prefix + from));
        }
        for (Map.Entry<String, ?> entry : saved.entrySet())
          if (entry.getKey().startsWith("partLength:" + from + ":"))
            changes.put(
                "partLength:" + to + entry.getKey().substring(("partLength:" + from).length()),
                entry.getValue());
      }
      for (Map.Entry<String, ?> entry : saved.entrySet())
        if ((entry.getKey().equals("last") || entry.getKey().startsWith("last:"))
            && from.equals(entry.getValue())) changes.put(entry.getKey(), to);
    }
    return changes;
  }

  static String filename(JSONObject source) {
    String name = source.optString("filename");
    if (!name.isEmpty()) return name;
    try {
      String path = new URI(source.optString("url")).getPath();
      return path.substring(path.lastIndexOf('/') + 1);
    } catch (Exception ignored) {
      return "";
    }
  }

  static Map<String, String> normalize(JSONObject data) throws Exception {
    if (Company.current != Company.WCW) return Collections.emptyMap();
    JSONArray rows = data.getJSONArray("episodes");
    Map<String, JSONObject> indexed = new HashMap<>();
    Map<String, String> historyMoves = new HashMap<>();
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      indexed.put(row.getString("date") + ":" + row.getString("show"), row);
    }
    int originalCount = rows.length();
    for (int i = 0; i < originalCount; i++) {
      JSONObject row = rows.getJSONObject(i);
      JSONArray sources = row.getJSONArray("sources");
      Set<String> movedTo = new HashSet<>();
      for (int j = sources.length() - 1; j >= 0; j--) {
        JSONObject source = sources.getJSONObject(j);
        if (!source.optString("type").equals("archive") || source.has("urls")) continue;
        String name = filename(source), show = ArchiveSync.family(name);
        if (show == null) continue;
        String date =
            ArchiveSync.fileDate(name, data, ArchiveSync.collection(source.optString("url")));
        if (date == null) date = row.getString("date");
        if (show.equals(row.optString("show")) && date.equals(row.optString("date"))) continue;
        String key = date + ":" + show;
        JSONObject target = indexed.get(key);
        if (target == null) {
          target =
              new JSONObject()
                  .put("id", date + ":" + CatalogSync.key(show))
                  .put("date", date)
                  .put("year", Integer.parseInt(date.substring(0, 4)))
                  .put("title", show)
                  .put("show", show)
                  .put("category", "weekly")
                  .put("description", "")
                  .put("sources", new JSONArray())
                  .put("discoveredAt", row.optLong("discoveredAt"));
          JSONObject art = data.optJSONObject("showArtwork");
          if (art != null && art.optJSONObject(show) != null)
            target.put("thumbnail", art.getJSONObject(show).optString("background"));
          indexed.put(key, target);
          rows.put(target);
        }
        JSONArray targetSources = target.getJSONArray("sources");
        boolean exists = false;
        for (int k = 0; k < targetSources.length(); k++)
          exists |=
              Catalog.sourceKey(source).equals(Catalog.sourceKey(targetSources.getJSONObject(k)));
        if (!exists) targetSources.put(source);
        sources.remove(j);
        movedTo.add(target.getString("id"));
      }
      if (sources.length() == 0 && movedTo.size() == 1)
        historyMoves.put(row.getString("id"), movedTo.iterator().next());
    }
    for (int i = rows.length() - 1; i >= 0; i--)
      if (rows.getJSONObject(i).getJSONArray("sources").length() == 0) rows.remove(i);
    return historyMoves;
  }
}
