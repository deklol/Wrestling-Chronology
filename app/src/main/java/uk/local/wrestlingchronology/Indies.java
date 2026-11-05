package uk.local.wrestlingchronology;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;
import org.json.*;

/** Reviewed TV promotion libraries share the regular catalogue and player. */
final class Indies {
  static boolean initialized;
  static final Map<String, String> countries = new LinkedHashMap<>();

  static synchronized void initialize(Context context) {
    if (initialized) return;
    try {
      JSONObject manifest = JsonStreams.read(context.getAssets().open("indies.json"));
      JSONArray rows = manifest.getJSONArray("companies");
      for (int i = 0; i < rows.length(); i++) {
        JSONObject row = rows.getJSONObject(i);
        String id = row.getString("id");
        JSONArray list = row.getJSONArray("shows");
        String[] shows = new String[list.length()];
        for (int j = 0; j < shows.length; j++) shows[j] = list.getString(j);
        Company.INDEPENDENT.add(new Company(id, row.getString("name"), row.getString("period"),
            0, "", "9999-12-31", shows, row.getString("country")));
        countries.put(id, row.getString("country"));
      }
      initialized = true;
    } catch (Exception error) {
      android.util.Log.w("Chronology", "Indie directory unavailable", error);
    }
  }

  static JSONObject library(Context context) throws Exception {
    try (InputStream in = new AtomicFile(Company.cache(context)).openRead()) {
      return validate(JsonStreams.read(in), Company.current.id);
    } catch (Exception ignored) {
      return validate(JsonStreams.read(context.getAssets().open("libraries/" + Company.current.id + ".json")), Company.current.id);
    }
  }

  static JSONObject validate(JSONObject data, String company) throws Exception {
    if (!company.equals(data.getString("company"))) throw new IOException("Library company mismatch");
    JSONArray rows = data.getJSONArray("episodes");
    HashSet<String> ids = new HashSet<>();
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      if (!ids.add(row.getString("id")) || row.getJSONArray("sources").length() == 0)
        throw new IOException("Invalid indie library");
      row.getString("title"); row.getString("show"); row.getString("date"); row.getInt("year");
    }
    return data;
  }

  static boolean containsSource(String company, String episode, JSONObject source) {
    if (!Company.current.indie || !Company.current.id.equals(company)) return false;
    Catalog.Episode row = Catalog.find(episode);
    if (row == null) return false;
    for (JSONObject candidate : row.sources) {
      boolean same = true;
      for (String key : new String[]{"type", "url", "urls", "videoId", "videoIds"})
        same &= candidate.optString(key).equals(source.optString(key));
      if (same) return true;
    }
    return false;
  }

  static CatalogSync.Result refresh(Context context) throws Exception {
    Company company = Company.current;
    JSONObject response = new CommunityApi(context).request(CollectionApi.PATH,
        new JSONObject().put("action", "indieLibrary").put("company", company.id));
    JSONObject data = validate(response, company.id);
    AtomicFile file = new AtomicFile(Company.cache(context));
    FileOutputStream out = file.startWrite();
    try { JsonStreams.write(out, data); file.finishWrite(out); }
    catch (Exception error) { file.failWrite(out); throw error; }
    if (Company.current != company) return new CatalogSync.Result(0, 0);
    int before = Catalog.episodes.size();
    Catalog.replace(data);
    Catalog.prefs(context).edit().putLong("last_refresh", System.currentTimeMillis()).apply();
    return new CatalogSync.Result(Math.max(0, Catalog.episodes.size() - before), 0);
  }
}
