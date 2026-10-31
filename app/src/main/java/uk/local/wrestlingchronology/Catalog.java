package uk.local.wrestlingchronology;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

final class Catalog {
  static volatile List<Episode> episodes = Collections.emptyList();
  static JSONObject raw;
  static JSONObject loadedPack;
  static volatile Set<String> hiddenShows = Collections.emptySet();

  static synchronized void load(Context context) throws Exception {
    if (!episodes.isEmpty()) return;
    hiddenShows = new HashSet<>(prefs(context).getStringSet("hidden_shows", Collections.emptySet()));
    long started = android.os.SystemClock.elapsedRealtime();
    JSONObject cached = RemoteLibrary.cached(context);
    JSONObject pack;
    if (cached != null && cached.optLong("revision") >= RemoteLibrary.bundledRevision(context)) {
      pack = cached;
    } else {
      JSONObject seed = RemoteLibrary.bundled(context);
      pack = cached == null ? seed : LibraryPack.merge(cached, seed);
    }
    raw = pack.getJSONObject("catalogue");
    SourceConfig.apply(raw, pack.getJSONObject("sources"));
    Map<String, String> moves = new HashMap<>();
    if (Company.current == Company.WCW && raw.optInt("sourceValidation") < 5) {
      moves.putAll(normalizeYoutube(raw));
      raw.put("sourceValidation", 5);
    }
    boolean needsIdentityCheck = raw.optInt("archiveIdentityVersion") != ArchiveIdentity.VERSION;
    if (needsIdentityCheck) moves.putAll(ArchiveIdentity.normalize(raw));
    raw.put("archiveIdentityVersion", ArchiveIdentity.VERSION);
    moves.putAll(ArchiveIdentity.configuredMoves(raw));
    if (!moves.isEmpty()) {
      SharedPreferences preferences = prefs(context);
      SharedPreferences.Editor editor = preferences.edit();
      Map<String, ?> saved = preferences.getAll();
      for (Map.Entry<String, Object> entry :
          ArchiveIdentity.historyChanges(saved, moves).entrySet()) {
        Object value = entry.getValue();
        if (value instanceof String) editor.putString(entry.getKey(), (String) value);
        else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
        else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
        else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
      }
      editor.apply();
    }
    replace(raw, false);
    boolean cacheFormatChanged = pack.optInt("runtimeCacheVersion") < 2;
    pack.put("runtimeCacheVersion", 2);
    loadedPack = pack;
    if (pack != cached || needsIdentityCheck || cacheFormatChanged) {
      try { RemoteLibrary.saveCached(context, pack); }
      catch (Exception error) { android.util.Log.i("Chronology", "Library cache write deferred", error); }
    }
    android.util.Log.i("Chronology", "LIBRARY_READY " + Company.current.id + " episodes=" + episodes.size()
        + " ms=" + (android.os.SystemClock.elapsedRealtime() - started));
    if (!episodes.isEmpty()) context.getSharedPreferences("application", 0).edit()
        .putInt("availableYear:" + Company.current.id, years().last()).apply();
  }

  static Map<String, String> normalizeYoutube(JSONObject data) throws Exception {
    Map<String, String> moves = new HashMap<>();
    JSONArray rows = data.getJSONArray("episodes");
    for (int i = rows.length() - 1; i >= 0; i--) {
      JSONObject row = rows.getJSONObject(i);
      JSONArray sources = row.getJSONArray("sources");
      Set<String> movedTo = new HashSet<>();
      for (int j = sources.length() - 1; j >= 0; j--) {
        JSONObject source = sources.getJSONObject(j);
        if (!source.optString("type").equals("youtube")) continue;
        if (source.optBoolean("direct")) continue; // Explicit source-manifest broadcast identity.
        String date = CatalogSync.date(source.optString("title"), data),
            show = CatalogSync.show(source.optString("title"));
        if (row.optString("date").equals(date) && row.optString("show").equals(show)) continue;
        if (date != null)
          for (int k = 0; k < rows.length(); k++) {
            JSONObject target = rows.getJSONObject(k);
            if (target.optString("date").equals(date) && target.optString("show").equals(show)) {
              JSONArray options = target.getJSONArray("sources");
              boolean exists = false;
              for (int n = 0; n < options.length(); n++)
                exists |= sourceKey(options.getJSONObject(n)).equals(sourceKey(source));
              if (!exists) options.put(source);
              movedTo.add(target.getString("id"));
              break;
            }
          }
        sources.remove(j);
      }
      if (sources.length() == 0) {
        if (movedTo.size() == 1) moves.put(row.getString("id"), movedTo.iterator().next());
        rows.remove(i);
      }
    }
    return moves;
  }

  static void mergeSeed(JSONObject cache, JSONObject seed) throws JSONException {
    JSONArray rows = cache.getJSONArray("episodes"), bundled = seed.getJSONArray("episodes");
    Map<String, JSONObject> known = new HashMap<>();
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      known.put(seedIdentity(row), row);
    }
    for (int i = 0; i < bundled.length(); i++) {
      JSONObject row = bundled.getJSONObject(i),
          existing = known.get(seedIdentity(row));
      if (existing == null) {
        rows.put(row);
        known.put(seedIdentity(row), row);
        continue;
      }
      for (String key : new String[] {"poster", "description", "title"})
        if (!row.optString(key).isEmpty()) existing.put(key, row.get(key));
      JSONArray sources = existing.getJSONArray("sources"),
          newSources = row.getJSONArray("sources");
      Set<String> keys = new HashSet<>();
      Map<String, JSONObject> byKey = new HashMap<>();
      for (int j = 0; j < sources.length(); j++) {
        JSONObject source = sources.getJSONObject(j);
        keys.add(sourceKey(source));
        byKey.put(sourceKey(source), source);
      }
      // Refresh audited metadata on existing URLs as well as adding new sources.
      for (int j = 0; j < newSources.length(); j++) {
        JSONObject source = newSources.getJSONObject(j), old = byKey.get(sourceKey(source));
        if (old != null)
          for (String field :
              new String[] {
                "duration", "creator", "height", "label", "programme", "language", "audioLanguage"
              }) if (source.has(field)) old.put(field, source.get(field));
      }
      for (int j = 0; j < newSources.length(); j++)
        if (keys.add(sourceKey(newSources.getJSONObject(j))))
          sources.put(newSources.getJSONObject(j));
    }
    cache.put("eventCalendar", seed.getJSONArray("eventCalendar"));
    cache.put("playlists", seed.getJSONArray("playlists"));
    cache.put("archiveItems", seed.getJSONArray("archiveItems"));
    if (seed.has("historyMigrations"))
      cache.put("historyMigrations", seed.getJSONObject("historyMigrations"));
    if (seed.has("sourceAssignments"))
      cache.put("sourceAssignments", seed.getJSONObject("sourceAssignments"));
    SourceCorrections.apply(cache, null);
  }

  static String sourceKey(JSONObject source) {
    return source.optString("type")
        + ":"
        + source.optString("videoId", source.optString("url", source.optString("urls")));
  }

  private static String seedIdentity(JSONObject row) throws JSONException {
    // Separate broadcasts can share a date, particularly a PPV and its pre-show.
    return "PPV".equals(row.getString("show"))
        ? row.getString("id")
        : row.getString("date") + ":" + row.getString("show");
  }

  static ArrayList<Episode> recent(String show) {
    ArrayList<Episode> rows = new ArrayList<>();
    for (Episode item : episodes) if (item.show.equals(show)) rows.add(item);
    rows.sort(
        Comparator.comparingLong((Episode item) -> item.addedAt)
            .reversed()
            .thenComparing((Episode item) -> item.date, Comparator.reverseOrder()));
    if (rows.size() > 20) rows.subList(20, rows.size()).clear();
    return rows;
  }

  static synchronized void replace(JSONObject data) throws Exception { replace(data, true); }

  private static synchronized void replace(JSONObject data, boolean normalize) throws Exception {
    if (normalize) ArchiveIdentity.normalize(data);
    ArrayList<Episode> next = new ArrayList<>();
    JSONArray rows = data.getJSONArray("episodes");
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      JSONArray sources = row.getJSONArray("sources");
      for (int j = 0; j < sources.length(); j++)
        if (sources.getJSONObject(j).optString("type").equals("archive"))
          sources.getJSONObject(j).put("programme", row.getString("show"));
      Episode item = new Episode(rows.getJSONObject(i));
      // Eligibility changes the view, not the stored source catalogue. Removing
      // the final source here produced invalid cached rows and forced reimports.
      for (int j = item.sources.size() - 1; j >= 0; j--)
        if (!SourcePolicy.eligible(item.sources.get(j))) item.sources.remove(j);
      if (item.date.compareTo(Company.current.firstDate) >= 0
          && item.date.compareTo(Company.current.lastDate) <= 0
          && !item.sources.isEmpty()) next.add(item);
    }
    next.sort(
        Comparator.comparing((Episode item) -> item.date)
            .thenComparingInt(item -> showOrder(item.show))
            .thenComparingInt(item -> item.json.optInt("eventOrder", 0))
            .thenComparing(item -> item.title));
    episodes = Collections.unmodifiableList(next);
    raw = data;
  }

  static int showOrder(String show) {
    return Arrays.asList(
            "All Nighter",
            "Saturday Night",
            "Worldwide",
            "Pro",
            "Power Hour",
            "Main Event",
            "Prime",
            "Raw",
            "SmackDown",
            "Heat",
            "Hardcore TV",
            "ECW on TNN",
            "Impact",
            "Weekly PPV",
            "Nitro",
            "Thunder",
            "Clash of the Champions",
            "PPV")
        .indexOf(show);
  }

  static synchronized ArrayList<Episode> forYear(int year, String filter, boolean extras) {
    ArrayList<Episode> result = new ArrayList<>();
    for (Episode item : episodes)
      if (item.year == year
          && (!filter.equals("All") || !hiddenShows.contains(item.show))
          && (!filter.equals("All") || year < 1997 || extras || item.main())
          && (filter.equals("All") || filter.equals(item.show))) result.add(item);
    return result;
  }

  static TreeSet<Integer> years() {
    TreeSet<Integer> result = new TreeSet<>();
    for (Episode item : episodes) result.add(item.year);
    return result;
  }

  static boolean weeklyShow(String show) {
    return !show.equals("PPV") && !show.equals("Specials")
        && !show.equals("Live event") && !show.equals("One Night Only");
  }

  static String showLabel(String show, int year) {
    if (show.equals("Saturday Night") && Company.current == Company.WWE) return "Saturday Night’s Main Event";
    if (show.equals("Saturday Night") && Company.current == Company.WCW) {
      if (year > 0 && year < 1992) return "World Championship Wrestling";
      if (year == 1992) return "WCW TV / Saturday Night";
    }
    return show;
  }

  static synchronized Episode find(String id) {
    for (Episode item : episodes) if (item.id.equals(id)) return item;
    return null;
  }

  static String read(InputStream stream) throws IOException {
    try (InputStream input = stream;
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16384];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        output.write(buffer, 0, count);
        if (output.size() > 48 * 1024 * 1024) throw new IOException("Response too large");
      }
      return output.toString(StandardCharsets.UTF_8.name());
    }
  }

  static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences("history_" + Company.current.id, Context.MODE_PRIVATE);
  }

  static long position(Context context, Episode item) {
    return prefs(context).getLong("pos:" + item.id, 0);
  }

  static boolean watched(Context context, Episode item) {
    return prefs(context).getBoolean("watched:" + item.id, false);
  }

  static Episode continueWatching(Context context) {
    return continueWatching(episodes, prefs(context).getAll());
  }

  static ArrayList<Episode> watchHistory(List<Episode> items, Map<String, ?> history) {
    ArrayList<Episode> result = new ArrayList<>();
    for (Episode item : items) {
      if (Boolean.TRUE.equals(history.get("watched:" + item.id))
          || savedNumber(history, "pos:" + item.id) > 0
          || savedNumber(history, "seen:" + item.id) > 0)
        result.add(item);
    }
    result.sort(Comparator.<Episode>comparingLong(item -> savedNumber(history, "played:" + item.id))
        .reversed().thenComparing(item -> item.id));
    return result;
  }

  /** History belongs to the selected company; a failed new selection must not hide progress. */
  static Episode continueWatching(List<Episode> library, Map<String, ?> history) {
    Episode latest = null;
    long latestTime = -1;
    String last = (String) history.get("last");
    for (Episode item : library) {
      if (Boolean.TRUE.equals(history.get("watched:" + item.id))) continue;
      if (savedNumber(history, "pos:" + item.id) <= 0
          && savedNumber(history, "part:" + item.id) <= 0
          && savedNumber(history, "seen:" + item.id) <= 0) continue;
      long time = savedNumber(history, "played:" + item.id);
      if (latest == null
          || time > latestTime
          || (time == latestTime
              && (item.id.equals(last)
                  || (!latest.id.equals(last) && item.date.compareTo(latest.date) > 0)))) {
        latest = item;
        latestTime = time;
      }
    }
    return latest;
  }

  private static long savedNumber(Map<String, ?> history, String key) {
    Object value = history.get(key);
    return value instanceof Number ? ((Number) value).longValue() : 0;
  }

  /** Index into the current, filtered timeline; jumping never changes the playback queue. */
  static int firstInMonth(List<Episode> items, int month) {
    if (month < 1 || month > 12) return -1;
    String marker = String.format(Locale.ROOT, "-%02d-", month);
    for (int i = 0; i < items.size(); i++) {
      String date = items.get(i).date;
      if (date.length() == 10 && date.substring(4, 8).equals(marker)) return i;
    }
    return -1;
  }

  static long displayPosition(Context context, Episode item) {
    return prefs(context)
        .getLong(
            "seen:" + item.id,
            prefs(context).getInt("part:" + item.id, 0) == 0 ? position(context, item) : 0);
  }

  static long displayDuration(Context context, Episode item) {
    return prefs(context).getLong("length:" + item.id, item.duration * 1000L);
  }

  static int progressPercent(long position, long duration, boolean watched) {
    if (watched) return 100;
    if (duration <= 0 || position <= 0) return 0;
    return (int) Math.min(99, Math.max(1, 100d * position / duration));
  }

  static void progress(Context context, Episode item, long position, boolean watched) {
    prefs(context)
        .edit()
        .putLong("pos:" + item.id, watched ? 0 : Math.max(0, position))
        .putBoolean("watched:" + item.id, watched)
        .putString("last:" + item.year, item.id)
        .putString("last", item.id)
        .putLong("played:" + item.id, System.currentTimeMillis())
        .apply();
  }

  static String dateLabel(String iso) {
    try {
      return new java.text.SimpleDateFormat("d MMM yyyy", Locale.UK)
          .format(new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.UK).parse(iso));
    } catch (Exception ignored) {
      return iso;
    }
  }

  static final class Episode {
    final JSONObject json;
    final String id, date, title, show, description, thumbnail, poster;
    final long addedAt;
    final int year, duration;
    final ArrayList<JSONObject> sources = new ArrayList<>();

    Episode(JSONObject data) throws JSONException {
      json = data;
      id = data.getString("id");
      date = data.getString("date");
      year = data.getInt("year");
      title = data.getString("title");
      String category = data.getString("show");
      show = category.equals("Clash of the Champions") ? "PPV" : category;
      description = data.optString("description");
      thumbnail = data.optString("thumbnail");
      poster = data.optString("poster");
      addedAt = data.optLong("discoveredAt");
      duration = data.optInt("duration");
      JSONArray list = data.optJSONArray("sources");
      if (list != null) for (int i = 0; i < list.length(); i++) sources.add(list.getJSONObject(i));
    }

    boolean main() {
      return Company.current != Company.WCW
          || show.equals("Nitro")
          || show.equals("Thunder")
          || show.equals("PPV")
          || show.equals("Clash of the Champions");
    }

    String sourceLabel() {
      boolean archive = false, youtube = false, daily = false, okru = false;
      for (JSONObject source : sources) {
        archive |= source.optString("type").equals("archive");
        daily |= source.optString("type").equals("dailymotion");
        okru |= source.optString("type").equals("okru");
        youtube |= source.optString("type").equals("youtube");
      }
      if (okru) {
        ArrayList<String> labels = new ArrayList<>();
        if (archive) labels.add("Archive");
        if (youtube) labels.add("YouTube");
        if (daily) labels.add("Dailymotion");
        labels.add("OK.ru");
        return android.text.TextUtils.join(" + ", labels);
      }
      if (daily && !archive && !youtube) return "Dailymotion";
      return archive && youtube
          ? "Archive + YouTube"
          : youtube ? "Official YouTube" : "Internet Archive";
    }

    String heading() {
      return show.equals("PPV")
          ? title
          : Company.current == Company.WCW && show.equals("Saturday Night") && date.compareTo("1992-04-04") < 0
              ? "World Championship Wrestling"
              : show.equals("Saturday Night") && Company.current == Company.WWE ? "Saturday Night’s Main Event" : show;
    }
  }
}
