package uk.local.wrestlingchronology;

import java.util.*;
import org.json.*;

/** Reviewed recording assignments repair old caches without dropping other sources. */
final class SourceCorrections {
  static void apply(JSONObject catalogue, JSONObject config) throws JSONException {
    JSONArray rows = catalogue.getJSONArray("episodes");
    JSONObject assignments = catalogue.optJSONObject("sourceAssignments");
    Map<String, String> confirmed = new HashMap<>();
    if (assignments != null) {
      for (int i = 0; i < rows.length(); i++) {
        JSONObject row = rows.getJSONObject(i);
        JSONArray sources = row.getJSONArray("sources");
        String id = row.getString("id");
        for (int j = 0; j < sources.length(); j++) {
          String key = Catalog.sourceKey(sources.getJSONObject(j));
          if (id.equals(assignments.optString(key))) confirmed.put(key, id);
        }
      }
    }
    Set<String> retired = new HashSet<>();
    JSONArray removals = config == null ? null : config.optJSONArray("retiredSources");
    if (removals != null)
      for (int i = 0; i < removals.length(); i++) retired.add(removals.getString(i));
    for (int i = rows.length() - 1; i >= 0; i--) {
      JSONObject row = rows.getJSONObject(i);
      String id = row.getString("id");
      JSONArray kept = new JSONArray(), sources = row.getJSONArray("sources");
      for (int j = 0; j < sources.length(); j++) {
        JSONObject source = sources.getJSONObject(j);
        String key = Catalog.sourceKey(source);
        if (!retired.contains(key) && id.equals(confirmed.getOrDefault(key, id))) kept.put(source);
      }
      if (kept.length() == 0 && sources.length() > 0) rows.remove(i);
      else row.put("sources", kept);
    }
  }
}
