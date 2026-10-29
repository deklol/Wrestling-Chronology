package uk.local.wrestlingchronology;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.net.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import org.json.*;

final class CatalogSync {
  static volatile boolean busy;
  static volatile boolean verifyFailNextPlaylist;
  static volatile int completed, total;
  static volatile String status = "";
  static final ArrayList<String> warnings = new ArrayList<>();
  static final ArrayList<String> scanReport = new ArrayList<>();

  static final class Deferred extends IOException {
    Deferred(String reason) {
      super(reason);
    }
  }

  static String reason(Exception error) {
    if (error instanceof java.net.SocketTimeoutException) return "connection timed out";
    String message = error.getMessage();
    if (message == null) return error.getClass().getSimpleName();
    String safe = message.replaceAll("https?://\\S+", "host");
    return safe.substring(0, Math.min(140, safe.length()));
  }

  static void warning(String message) {
    warnings.add(message);
    scanReport.add("SKIPPED · " + message);
    android.util.Log.w("WCWCatalog", "SOURCE_CHECK_FAILED " + message);
  }

  static void checked(String source, int added, boolean unchanged) {
    String line =
        source
            + " · "
            + (unchanged
                ? "unchanged"
                : added > 0 ? added + " new sources" : "checked; no new sources");
    scanReport.add(line);
    android.util.Log.i("WCWCatalog", "SOURCE_CHECK_OK " + line);
  }

  static void progress(int done, int steps, String label) {
    completed = done;
    total = steps;
    status = label;
  }

  static final class Result {
    final int episodes, sources;

    Result(int episodes, int sources) {
      this.episodes = episodes;
      this.sources = sources;
    }
  }

  static final long INTERVAL = 6 * 60 * 60 * 1000L;
  static final String CHANNEL = "UCbGG2hv4cWmIrBAIhGiCdSg";
  static final Pattern ALIAS = Patterns.get("fall brawl\\s*[-:]?\\s*war\\s*games"),
      NOISE = Patterns.get("full (?:episode|event)|wcw|nwo|nwa|\\bthe\\b|\\b(?:19|20)\\d{2}\\b"),
      NON_KEY = Patterns.get("[^a-z0-9]");
  static final Map<String, String> keyCache =
      new LinkedHashMap<String, String>(1024, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> entry) {
          return size() > 3000;
        }
      };

  static String get(String url) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(12000);
    connection.setReadTimeout(20000);
    connection.setRequestProperty("User-Agent", "Mozilla/5.0");
    connection.setRequestProperty("Accept-Language", "en-GB,en;q=0.9");
    try {
      if (connection.getResponseCode() != 200) throw new IOException("Host unavailable");
      return Catalog.read(connection.getInputStream());
    } finally {
      connection.disconnect();
    }
  }

  static JSONObject post(String url, JSONObject payload) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(12000);
    connection.setReadTimeout(20000);
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("User-Agent", "Mozilla/5.0");
    connection.setRequestProperty("Content-Type", "application/json");
    try {
      try (OutputStream out = connection.getOutputStream()) {
        out.write(payload.toString().getBytes("UTF-8"));
      }
      if (connection.getResponseCode() != 200)
        throw new IOException("Playlist continuation unavailable");
      return new JSONObject(Catalog.read(connection.getInputStream()));
    } finally {
      connection.disconnect();
    }
  }

  static void collect(Object value, String key, List<JSONObject> result) throws JSONException {
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      Iterator<String> keys = object.keys();
      while (keys.hasNext()) {
        String name = keys.next();
        Object child = object.get(name);
        if (name.equals(key) && child instanceof JSONObject) result.add((JSONObject) child);
        collect(child, key, result);
      }
    } else if (value instanceof JSONArray) {
      JSONArray list = (JSONArray) value;
      for (int i = 0; i < list.length(); i++) collect(list.get(i), key, result);
    }
  }

  static String richText(JSONObject object) {
    if (object == null) return "";
    if (object.has("simpleText")) return object.optString("simpleText");
    StringBuilder result = new StringBuilder();
    JSONArray runs = object.optJSONArray("runs");
    if (runs != null)
      for (int i = 0; i < runs.length(); i++)
        result.append(runs.optJSONObject(i).optString("text"));
    return result.toString();
  }

  static String identity(JSONObject node, Set<String> fullShows) throws Exception {
    String id = node.optString("contentId", node.optString("videoId")), title, length = "";
    if (!id.matches("[A-Za-z0-9_-]{11}")) return null;
    if (node.has("contentId")) {
      if (!node.optString("contentType").equals("LOCKUP_CONTENT_TYPE_VIDEO")) return null;
      title =
          node.getJSONObject("metadata")
              .getJSONObject("lockupMetadataViewModel")
              .getJSONObject("title")
              .getString("content");
      ArrayList<JSONObject> badges = new ArrayList<>();
      collect(node.optJSONObject("contentImage"), "thumbnailBadgeViewModel", badges);
      for (JSONObject badge : badges)
        if (badge.optString("text").matches("[0-9:]+")) {
          length = badge.optString("text");
          break;
        }
    } else {
      title = richText(node.optJSONObject("title"));
      length = richText(node.optJSONObject("lengthText"));
    }
    int duration = 0;
    for (String part : length.split(":"))
      try {
        duration = duration * 60 + Integer.parseInt(part);
      } catch (NumberFormatException ignored) {
      }
    if (duration >= 2700
        && title.matches("(?is).*\\bFULL (?:EPISODE|EVENT)\\b.*")
        && !title.matches("(?is).*(?:FULL MATCH|watch party|compilation|^LIVE:).*"))
      fullShows.add(id);
    return id + ":" + title + ":" + length;
  }

  static synchronized String key(String value) {
    String cached = keyCache.get(value);
    if (cached != null) return cached;
    String cleaned = NOISE.matcher(value.toLowerCase(Locale.UK)).replaceAll("");
    String result = NON_KEY.matcher(ALIAS.matcher(cleaned).replaceAll("fall brawl")).replaceAll("");
    keyCache.put(value, result);
    return result;
  }

  static String show(String title) {
    String value = title.toLowerCase(Locale.UK);
    if (value.contains("full event"))
      return value.contains("clash of") ? "Clash of the Champions" : "PPV";
    return value.contains("nitro")
        ? "Nitro"
        : value.contains("thunder")
            ? "Thunder"
            : value.contains("clash of")
                ? "Clash of the Champions"
                : value.contains("full episode")
                    ? Optional.ofNullable(ArchiveSync.family(title)).orElse("Saturday Night")
                    : "PPV";
  }

  static String validDate(int year, int month, int day) {
    try {
      String date = LocalDate.of(year, month, day).toString();
      return date.compareTo(Company.current.firstDate) >= 0
              && date.compareTo(Company.current.lastDate) <= 0
          ? date
          : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  static String date(String title, JSONObject catalogue) throws Exception {
    Matcher clash =
        Patterns.get("(?i)clash of (?:the )?champions[ ._-]+([IVX]+|\\d{1,2})\\b")
            .matcher(title);
    if (clash.find()) {
      String number = clash.group(1).toUpperCase(Locale.UK);
      int value = 0;
      if (number.matches("\\d+")) value = Integer.parseInt(number);
      else
        for (int i = 0; i < number.length(); i++) {
          int digit = number.charAt(i) == 'X' ? 10 : number.charAt(i) == 'V' ? 5 : 1;
          int next =
              i + 1 < number.length()
                  ? number.charAt(i + 1) == 'X' ? 10 : number.charAt(i + 1) == 'V' ? 5 : 1
                  : 0;
          value += digit < next ? -digit : digit;
        }
      JSONArray events = catalogue.optJSONArray("eventCalendar");
      if (events != null)
        for (int i = 0; i < events.length(); i++)
          if (events.getJSONObject(i).optInt("clashNumber") == value)
            return events.getJSONObject(i).optString("date");
    }
    Matcher numeric =
        Patterns.get("\\b(19\\d{2}|20\\d{2})[-.](\\d{2})[-.](\\d{2})\\b").matcher(title);
    if (numeric.find())
      return validDate(
          Integer.parseInt(numeric.group(1)),
          Integer.parseInt(numeric.group(2)),
          Integer.parseInt(numeric.group(3)));
    Matcher named =
        Patterns.get(
                "\\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?[,]?\\s+(19\\d{2}|20\\d{2})",
                Pattern.CASE_INSENSITIVE)
            .matcher(title);
    if (named.find()) {
      int month =
          Arrays.asList(
                      "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov",
                      "dec")
                  .indexOf(named.group(1).substring(0, 3).toLowerCase(Locale.UK))
              + 1;
      return validDate(Integer.parseInt(named.group(3)), month, Integer.parseInt(named.group(2)));
    }
    Matcher yearMatch = Patterns.get("\\b(19\\d{2}|20\\d{2})\\b").matcher(title);
    String year = yearMatch.find() ? yearMatch.group(1) : null;
    JSONArray calendar = catalogue.optJSONArray("eventCalendar");
    String best = null, needle = key(title);
    int length = -1;
    boolean ambiguous = false;
    if (calendar != null)
      for (int i = 0; i < calendar.length(); i++) {
        JSONObject event = calendar.getJSONObject(i);
        String when = event.optString("date");
        if (year != null && !when.startsWith(year)) continue;
        String name = key(event.optString("name"));
        if (year != null && !needle.contains(name)) {
          JSONArray aliases = event.optJSONArray("aliases");
          if (aliases != null)
            for (int j = 0; j < aliases.length(); j++) {
              String alias = key(aliases.getString(j));
              if (alias.length() > 3 && needle.contains(alias)) {
                name = alias;
                break;
              }
            }
        }
        if (name.length() < 3 || !needle.contains(name)) continue;
        if (name.length() > length) {
          length = name.length();
          best = when;
          ambiguous = false;
        } else if (name.length() == length && !when.equals(best)) ambiguous = true;
      }
    if (best != null && !ambiguous)
      return best.compareTo("1983-01-01") >= 0 && best.compareTo("2001-03-26") <= 0 ? best : null;
    JSONArray episodes = catalogue.getJSONArray("episodes");
    best = null;
    length = -1;
    ambiguous = false;
    for (int i = 0; i < episodes.length(); i++) {
      JSONObject episode = episodes.getJSONObject(i);
      String when = episode.getString("date");
      if (!episode.getString("show").equals("PPV") || (year != null && !when.startsWith(year)))
        continue;
      String name = key(episode.getString("title"));
      if (name.length() < 3 || !needle.contains(name)) continue;
      if (name.length() > length) {
        length = name.length();
        best = when;
        ambiguous = false;
      } else if (name.length() == length && !when.equals(best)) ambiguous = true;
    }
    return ambiguous ? null : best;
  }

  static int merge(JSONObject data, JSONObject node, Set<String> seen, String playlistId)
      throws Exception {
    String title, id, length = "";
    if (node.has("contentId")) {
      if (!node.optString("contentType").equals("LOCKUP_CONTENT_TYPE_VIDEO")) return 0;
      title =
          node.getJSONObject("metadata")
              .getJSONObject("lockupMetadataViewModel")
              .getJSONObject("title")
              .getString("content");
      id = node.getString("contentId");
      ArrayList<JSONObject> badges = new ArrayList<>();
      collect(node.optJSONObject("contentImage"), "thumbnailBadgeViewModel", badges);
      for (JSONObject badge : badges)
        if (badge.optString("text").matches("[0-9:]+")) {
          length = badge.getString("text");
          break;
        }
    } else {
      title = richText(node.optJSONObject("title"));
      id = node.optString("videoId");
      length = richText(node.optJSONObject("lengthText"));
    }
    if (!id.matches("[A-Za-z0-9_-]{11}")) return 0;
    if (!Patterns.get("\\bFULL (?:EPISODE|EVENT)\\b", Pattern.CASE_INSENSITIVE)
            .matcher(title)
            .find()
        || Patterns.get("FULL MATCH|watch party|compilation|^LIVE:", Pattern.CASE_INSENSITIVE)
            .matcher(title)
            .find()) return 0;
    int duration = 0;
    for (String part : length.split(":")) {
      try {
        duration = duration * 60 + Integer.parseInt(part);
      } catch (NumberFormatException ignored) {
        return 0;
      }
    }
    if (duration < (title.toLowerCase(Locale.UK).contains("full episode") ? 1200 : 2700)) return 0;
    String when = date(title, data);
    if (when == null) return 0;
    seen.add(id);
    String family = show(title);
    JSONArray episodes = data.getJSONArray("episodes");
    JSONObject selected = null;
    for (int i = 0; i < episodes.length(); i++) {
      JSONObject candidate = episodes.getJSONObject(i);
      if (candidate.getString("date").equals(when) && candidate.getString("show").equals(family)) {
        selected = candidate;
        break;
      }
    }
    if (selected == null) {
      String name =
          family.equals("PPV")
              ? title.replaceFirst("(?i)^FULL EVENT:\\s*", "").split(" \\| ")[0]
              : family;
      selected =
          new JSONObject()
              .put("id", when + ":" + key(name))
              .put("date", when)
              .put("year", Integer.parseInt(when.substring(0, 4)))
              .put("title", name)
              .put("show", family)
              .put("category", family.equals("PPV") ? "ppv" : "weekly")
              .put("part", 0)
              .put("description", "")
              .put("sources", new JSONArray())
              .put("discoveredAt", System.currentTimeMillis());
      episodes.put(selected);
    }
    JSONArray sources = selected.getJSONArray("sources");
    for (int i = 0; i < sources.length(); i++)
      if (id.equals(sources.getJSONObject(i).optString("videoId"))) return 0;
    sources.put(
        new JSONObject()
            .put("type", "youtube")
            .put("videoId", id)
            .put("channelId", SourceConfig.channelId)
            .put("playlistId", playlistId)
            .put("label", "Official WCW")
            .put("title", title)
            .put("duration", duration));
    selected
        .put("thumbnail", "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg")
        .put("duration", duration);
    return 1;
  }

  static void available() throws IOException {
    if (Thread.currentThread().isInterrupted())
      throw new Deferred("Fire TV paused the background task");
    if (PlayerActivity.playbackActive) throw new Deferred("Paused for playback");
  }

  static Result refresh(Context context) throws Exception {
    return refresh(context, false);
  }

  static Result refresh(Context context, boolean full) throws Exception {
    return RemoteLibrary.refresh(context);
  }

  static Result scanProviders(Context context, boolean full) throws Exception {
    synchronized (CatalogSync.class) {
      if (busy) return new Result(0, 0);
      busy = true;
    }
    long started = android.os.SystemClock.elapsedRealtime();
    warnings.clear();
    scanReport.clear();
    try {
      available();
      Catalog.prefs(context)
          .edit()
          .putLong("last_refresh_attempt", System.currentTimeMillis())
          .apply();
      JSONObject data = new JSONObject(Catalog.raw.toString());
      JSONArray playlists = data.getJSONArray("playlists");
      int added = 0;
      Set<String> seen = new HashSet<>();
      int before = data.getJSONArray("episodes").length();
      progress(
          0,
          playlists.length()
              + DirectYoutube.count(data)
              + (full
                  ? data.getJSONArray("archiveItems").length()
                  : Math.min(
                      SourceConfig.collectionsPerScan, data.getJSONArray("archiveItems").length())),
          "Checking official WCW playlists");
      for (int i = 0; i < playlists.length(); i++) {
        available();
        String playlistName = playlists.getJSONObject(i).optString("name", "official playlist");
        progress(i, total, "YouTube · " + playlistName);
        try {
          if (BuildConfig.DEBUG && verifyFailNextPlaylist) {
            verifyFailNextPlaylist = false;
            throw new IOException("simulated test timeout");
          }
          String playlistId = playlists.getJSONObject(i).getString("id");
          String html =
              MetadataCache.get(
                  context, "https://www.youtube.com/playlist?list=" + playlistId + "&hl=en&gl=GB");
          progress(i, total, "YouTube · " + playlistName);
          Matcher marker = Patterns.get("(?:var\\s+)?ytInitialData\\s*=\\s*").matcher(html);
          if (!marker.find()) throw new IOException("No playlist data");
          JSONObject page = (JSONObject) new JSONTokener(html.substring(marker.end())).nextValue();
          Matcher client =
              Patterns.get("\\\"INNERTUBE_CLIENT_VERSION\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                  .matcher(html);
          String version = client.find() ? client.group(1) : null;
          Set<String> tokens = new HashSet<>();
          ArrayList<JSONObject> playlistNodes = new ArrayList<>();
          for (int number = 0; number < 20; number++) {
            available();
            progress(
                i,
                total,
                "YouTube · " + playlistName + (number > 0 ? " · page " + (number + 1) : ""));
            collect(page, "playlistVideoRenderer", playlistNodes);
            collect(page, "lockupViewModel", playlistNodes);
            ArrayList<JSONObject> continuation = new ArrayList<>();
            collect(page, "continuationItemRenderer", continuation);
            String token = null;
            for (JSONObject item : continuation) {
              JSONObject endpoint = item.optJSONObject("continuationEndpoint");
              if (endpoint != null && endpoint.has("continuationCommand")) {
                token = endpoint.getJSONObject("continuationCommand").optString("token");
                break;
              }
            }
            if (token == null || token.isEmpty() || version == null || !tokens.add(token)) break;
            JSONObject contextData =
                new JSONObject()
                    .put(
                        "client",
                        new JSONObject()
                            .put("clientName", "WEB")
                            .put("clientVersion", version)
                            .put("hl", "en")
                            .put("gl", "GB"));
            page =
                post(
                    "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
                    new JSONObject().put("context", contextData).put("continuation", token));
          }
          ArrayList<String> identities = new ArrayList<>();
          for (JSONObject node : playlistNodes) {
            String identity = identity(node, seen);
            if (identity != null) identities.add(identity);
          }
          Collections.sort(identities);
          String fingerprint = MetadataCache.hash("parser-v5:" + String.join("\n", identities));
          JSONObject hashes = data.optJSONObject("playlistHashes");
          if (hashes == null) {
            hashes = new JSONObject();
            data.put("playlistHashes", hashes);
          }
          int playlistAdded = 0;
          boolean unchanged = fingerprint.equals(hashes.optString(playlistId));
          if (!unchanged) {
            for (JSONObject node : playlistNodes)
              playlistAdded += merge(data, node, seen, playlistId);
            hashes.put(playlistId, fingerprint);
          } else android.util.Log.i("WCWCatalog", "PLAYLIST_UNCHANGED id=" + playlistId);
          added += playlistAdded;
          checked("YouTube · " + playlistName, playlistAdded, unchanged);
        } catch (Deferred error) {
          throw error;
        } catch (Exception error) {
          warning("YouTube · " + playlistName + " · " + reason(error));
        }
      }
      if (playlists.length() > 0 && seen.isEmpty())
        warning("YouTube · no full shows returned; existing sources kept");
      added += DirectYoutube.refresh(context, data);
      added +=
          ArchiveSync.refresh(
              context,
              data,
              full ? data.getJSONArray("archiveItems").length() : SourceConfig.collectionsPerScan);
      available();
      ArchiveIdentity.normalize(data);
      AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "catalogue.json"));
      FileOutputStream output = file.startWrite();
      try {
        output.write(data.toString().getBytes("UTF-8"));
        file.finishWrite(output);
      } catch (Exception error) {
        file.failWrite(output);
        throw error;
      }
      Catalog.replace(data);
      int newEpisodes = data.getJSONArray("episodes").length() - before;
      android.content.SharedPreferences prefs = Catalog.prefs(context);
      prefs
          .edit()
          .putLong("last_refresh", System.currentTimeMillis())
          .putInt("archive_cursor", data.optInt("archiveCursor"))
          .putInt("pending_episodes", prefs.getInt("pending_episodes", 0) + newEpisodes)
          .putInt("pending_sources", prefs.getInt("pending_sources", 0) + added)
          .putBoolean("refresh_deferred", false)
          .putString(
              "last_refresh_error",
              warnings.isEmpty()
                  ? ""
                  : warnings.get(0)
                      + (warnings.size() > 1 ? " (" + warnings.size() + " checks failed)" : ""))
          .putLong(
              "refresh_retry_after",
              warnings.isEmpty() ? 0 : System.currentTimeMillis() + 15 * 60 * 1000L)
          .putString("last_scan_report", String.join("\n", scanReport))
          .putInt("last_scan_skipped", warnings.size())
          .apply();
      progress(total, total, "Library up to date");
      android.util.Log.i(
          "WCWCatalog",
          "REFRESH_OK added="
              + added
              + " newEpisodes="
              + newEpisodes
              + " youtube="
              + seen.size()
              + " episodes="
              + Catalog.episodes.size()
              + " ms="
              + (android.os.SystemClock.elapsedRealtime() - started));
      return new Result(newEpisodes, added);
    } catch (Deferred error) {
      Catalog.prefs(context).edit().putBoolean("refresh_deferred", true).apply();
      android.util.Log.i("WCWCatalog", "REFRESH_PAUSED reason=" + error.getMessage());
      throw error;
    } catch (Exception error) {
      String detail = status + " · " + reason(error);
      Catalog.prefs(context)
          .edit()
          .putString("last_refresh_error", detail)
          .putLong("refresh_retry_after", System.currentTimeMillis() + 15 * 60 * 1000L)
          .apply();
      android.util.Log.w("WCWCatalog", "REFRESH_FAILED " + detail);
      throw error;
    } finally {
      busy = false;
    }
  }
}
