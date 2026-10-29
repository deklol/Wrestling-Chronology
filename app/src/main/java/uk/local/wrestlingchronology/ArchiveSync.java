package uk.local.wrestlingchronology;

import android.content.Context;
import java.net.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Refresh only the public Archive collections supplied by the user's add-on. */
final class ArchiveSync {
  static final int BATCH = 12;

  static String collection(String url) {
    try {
      String[] parts = new URI(url).getPath().split("/");
      return parts.length > 2 ? parts[2] : "";
    } catch (Exception ignored) {
      return "";
    }
  }

  static String family(String title) {
    String value = title.replace('_', ' ').replace('.', ' ').toLowerCase(Locale.UK);
    // Anchored file markers, not letters occurring in a title or collection name.
    Matcher marker =
        Patterns.get(
                "(?i)^\\d{2}[ ._-]\\d{2}[ ._-]\\d{4}["
                    + " ._-]+(N|T|SN|COTC\\d*)(?:\\.ia)?\\.(?:mp4|mkv|m4v)$")
            .matcher(title);
    if (marker.matches()) {
      String code = marker.group(1).toUpperCase(Locale.UK);
      if (code.equals("N")) return "Nitro";
      if (code.equals("T")) return "Thunder";
      if (code.equals("SN")) return "Saturday Night";
      return "Clash of the Champions";
    }
    if (value.contains("clash of")) return "Clash of the Champions";
    boolean nitro = Patterns.get("\\b(?:nitro|ortin)\\b").matcher(value).find();
    boolean thunder = Patterns.get("\\b(?:thunder|rednuht)\\b").matcher(value).find();
    if (nitro && thunder) return null; // Mixed collection: never inherit one show's identity.
    if (nitro) return "Nitro";
    if (thunder) return "Thunder";
    if (value.contains("saturday night")) return "Saturday Night";
    if (value.matches(".*world[ ._-]*wide.*")) return "Worldwide";
    if (value.matches(".*\\b(?:wcw pro|pro wrestling)\\b.*")) return "Pro";
    if (value.contains("power hour")) return "Power Hour";
    if (value.contains("main event")) return "Main Event";
    if (Patterns.get("\\bprime\\b").matcher(value).find()) return "Prime";
    if (value.contains("world championship wrestling")) return "Saturday Night";
    return null;
  }

  static String fileDate(String name, JSONObject data) throws Exception {
    String clean = name.replace('_', ' ');
    Matcher iso =
        Patterns.get("\\b(19\\d{2}|20\\d{2})[ .-](\\d{2})[ .-](\\d{2})\\b").matcher(clean);
    if (iso.find())
      return CatalogSync.validDate(
          Integer.parseInt(iso.group(1)),
          Integer.parseInt(iso.group(2)),
          Integer.parseInt(iso.group(3)));
    Matcher numeric =
        Patterns.get("\\b(\\d{2})[ .-](\\d{2})[ .-](19\\d{2}|20\\d{2})\\b").matcher(clean);
    if (numeric.find()) {
      int a = Integer.parseInt(numeric.group(1)),
          b = Integer.parseInt(numeric.group(2)),
          year = Integer.parseInt(numeric.group(3));
      if (a > 12) return CatalogSync.validDate(year, b, a);
      if (b > 12) return CatalogSync.validDate(year, a, b);
      // Ambiguous dates are deliberately left unmapped.
      return null;
    }
    return CatalogSync.date(clean, data);
  }

  static String fileDate(String name, JSONObject data, String identifier) throws Exception {
    JSONObject orders = data.optJSONObject("archiveDateOrders");
    String order = orders == null ? "" : orders.optString(identifier);
    if (Patterns.get("\\b(?:19|20)\\d{2}[ ._-]\\d{2}[ ._-]\\d{2}\\b").matcher(name).find())
      return fileDate(name, data);
    Matcher date =
        Patterns.get("\\b(\\d{1,2})[ ._-](\\d{1,2})[ ._-](\\d{4}|\\d{2})\\b")
            .matcher(name.replace('_', ' '));
    if ((order.equals("MDY") || order.equals("DMY")) && date.find()) {
      int first = Integer.parseInt(date.group(1)), second = Integer.parseInt(date.group(2));
      int year = Integer.parseInt(date.group(3));
      if (year < 100) year += year >= 80 ? 1900 : 2000;
      return CatalogSync.validDate(
          year, order.equals("MDY") ? first : second, order.equals("MDY") ? second : first);
    }
    return fileDate(name, data);
  }

  static int merge(JSONObject data, String identifier, JSONObject item) throws Exception {
    JSONObject metadata = item.optJSONObject("metadata");
    if (item.optBoolean("is_dark") || metadata == null || metadata.optBoolean("access-restricted"))
      return 0;
    JSONArray rows = data.getJSONArray("episodes"), files = item.optJSONArray("files");
    if (files == null) return 0;
    ArrayList<String> signatures = new ArrayList<>();
    for (int i = 0; i < files.length(); i++) {
      JSONObject file = files.getJSONObject(i);
      if (file.optString("name").matches("(?i).+\\.(?:mp4|m4v)$"))
        signatures.add(
            file.optString("name")
                + ":"
                + file.optString("size")
                + ":"
                + file.optString("length")
                + ":"
                + file.optString("height")
                + ":"
                + file.optString("private")
                + ":"
                + file.optString("original"));
    }
    Collections.sort(signatures);
    String fingerprint =
        MetadataCache.hash(
            "parser-v6:"
                + data.optJSONObject("archiveDateOrders")
                + metadata.optString("creator")
                + String.join("\n", signatures));
    JSONObject hashes = data.optJSONObject("archiveHashes");
    if (hashes == null) {
      hashes = new JSONObject();
      data.put("archiveHashes", hashes);
    }
    if (fingerprint.equals(hashes.optString(identifier))) return 0;
    Set<String> families = new HashSet<>();
    Map<String, JSONObject> knownFiles = new HashMap<>();
    Set<String> urls = new HashSet<>();
    Map<String, JSONObject> existingSources = new HashMap<>();
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      JSONArray sources = row.getJSONArray("sources");
      for (int j = 0; j < sources.length(); j++) {
        JSONObject source = sources.getJSONObject(j);
        if (!source.optString("type").equals("archive")) continue;
        String url = source.optString("url");
        if (url.isEmpty()) continue;
        urls.add(url);
        existingSources.put(url, source);
        if (collection(url).equals(identifier)) {
          families.add(row.getString("show"));
          knownFiles.put(
              new URI(url).getPath().substring(("/download/" + identifier + "/").length()), row);
        }
      }
    }
    JSONObject hints = data.optJSONObject("archiveHints");
    String inherited =
        hints != null && hints.has(identifier)
            ? hints.getString(identifier)
            : families.size() == 1
                ? families.iterator().next()
                : family(metadata.optString("title"));
    String collectionTitle = metadata.optString("title").toLowerCase(Locale.UK);
    if ((collectionTitle.contains("ortin") || collectionTitle.contains("nitro"))
        && (collectionTitle.contains("rednuht") || collectionTitle.contains("thunder")))
      inherited = null;
    int added = 0;
    for (int i = 0; i < files.length(); i++) {
      JSONObject file = files.getJSONObject(i);
      String name = file.optString("name");
      JSONObject candidate =
          new JSONObject()
              .put("type", "archive")
              .put("filename", name)
              .put(
                  "programme",
                  Optional.ofNullable(family(name.replace('_', ' ')))
                      .orElse(inherited == null ? "" : inherited))
              .put("duration", file.optDouble("length", 0));
      if (!SourcePolicy.eligible(candidate)) continue;
      if (!name.matches("(?i).+\\.(?:mp4|m4v)$")
          || file.optBoolean("private")
          || name.contains("../")
          || Patterns.get(
                  "(?i)\\b(?:highlights?|trailer|preview|sample|compilation|clips?|full["
                      + " ._-]+match|part[ ._-]*\\d+)\\b")
              .matcher(name.replace('_', ' '))
              .find()) continue;
      if (Patterns.get("(?i)\\b(?:wwf|wwe|ecw|aew|roh)\\b")
          .matcher(name.replace('_', ' '))
          .find()) continue;
      // WCW/New Japan joint events belong in the library; unrelated NJPW recordings do not.
      if (Patterns.get("(?i)\\bnjpw\\b").matcher(name.replace('_', ' ')).find()
          && !Patterns.get("(?i)\\bwcw\\b").matcher(name.replace('_', ' ')).find()) continue;
      double duration = file.optDouble("length", 0);
      if (duration < SourcePolicy.minimumSeconds(candidate.optString("programme"))) continue;
      String url =
          "https://archive.org/download/"
              + identifier
              + "/"
              + URLEncoder.encode(name, "UTF-8").replace("+", "%20").replace("%2F", "/");
      if (urls.contains(url)) {
        existingSources
            .get(url)
            .put("duration", duration)
            .put("creator", metadata.optString("creator"))
            .put("language", metadata.optString("language"))
            .put("height", file.optInt("height"));
        continue;
      }
      String kind = family(name.replace('_', ' ')), when = fileDate(name, data, identifier);
      JSONObject selected = knownFiles.get(name);
      if (selected == null && !file.optString("original").isEmpty())
        selected = knownFiles.get(file.optString("original"));
      // A stale cached mapping must not overrule the file's explicit show or date.
      if (selected != null
          && ((kind != null && !kind.equals(selected.optString("show")))
              || (when != null && !when.equals(selected.optString("date"))))) selected = null;
      if (selected == null) {
        if (kind == null) kind = inherited;
        if (kind == null && when != null) {
          JSONArray calendar = data.getJSONArray("eventCalendar");
          for (int j = 0; j < calendar.length(); j++) {
            JSONObject event = calendar.getJSONObject(j);
            String key = CatalogSync.key(event.optString("name"));
            if (when.equals(event.optString("date"))
                && event.optString("category").equals("ppv")
                && key.length() > 3
                && CatalogSync.key(name).contains(key)) {
              kind = "PPV";
              break;
            }
          }
        }
        if (kind == null || when == null) continue;
        for (int j = 0; j < rows.length(); j++) {
          JSONObject row = rows.getJSONObject(j);
          if (when.equals(row.optString("date")) && kind.equals(row.optString("show"))) {
            selected = row;
            break;
          }
        }
        if (selected == null) {
          String title = kind, poster = "", description = "";
          if (kind.equals("PPV")) {
            title = null;
            JSONArray calendar = data.getJSONArray("eventCalendar");
            for (int j = 0; j < calendar.length(); j++) {
              JSONObject event = calendar.getJSONObject(j);
              if (when.equals(event.optString("date"))
                  && event.optString("category").equals("ppv")) {
                title = event.getString("name");
                poster = event.optString("poster");
                description = event.optString("description");
                break;
              }
            }
            if (title == null) continue;
          }
          selected =
              new JSONObject()
                  .put("id", when + ":" + CatalogSync.key(title))
                  .put("date", when)
                  .put("year", Integer.parseInt(when.substring(0, 4)))
                  .put("show", kind)
                  .put("title", title)
                  .put("poster", poster)
                  .put("description", description)
                  .put("duration", (int) duration)
                  .put("sources", new JSONArray())
                  .put("discoveredAt", System.currentTimeMillis());
          JSONObject art = data.optJSONObject("showArtwork");
          if (art != null && art.optJSONObject(kind) != null)
            selected.put("thumbnail", art.getJSONObject(kind).optString("background"));
          rows.put(selected);
        }
      }
      int height = file.optInt("height");
      selected
          .getJSONArray("sources")
          .put(
              new JSONObject()
                  .put("type", "archive")
                  .put("url", url)
                  .put("filename", name)
                  .put("programme", selected.optString("show"))
                  .put("duration", duration)
                  .put("creator", metadata.optString("creator"))
                  .put("language", metadata.optString("language"))
                  .put("height", height)
                  .put("bytes", file.optLong("size"))
                  .put("label", "Internet Archive" + (height > 0 ? " " + height + "p" : "")));
      urls.add(url);
      added++;
    }
    hashes.put(identifier, fingerprint);
    return added;
  }

  static int refresh(Context context, JSONObject data, int limit) throws Exception {
    JSONArray identifiers = data.getJSONArray("archiveItems");
    int cursor = Catalog.prefs(context).getInt("archive_cursor", 0),
        added = 0,
        count = Math.min(limit, identifiers.length());
    for (int i = 0; i < count; i++) {
      CatalogSync.available();
      String identifier = identifiers.getString((cursor + i) % identifiers.length());
      CatalogSync.progress(
          data.getJSONArray("playlists").length() + DirectYoutube.count(data) + i,
          data.getJSONArray("playlists").length() + DirectYoutube.count(data) + count,
          "Archive.org · " + identifier + " · " + (i + 1) + " / " + count);
      if (!identifier.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) continue;
      try {
        int found =
            merge(
                data,
                identifier,
                new JSONObject(
                    MetadataCache.get(context, "https://archive.org/metadata/" + identifier)));
        added += found;
        CatalogSync.checked("Archive.org · " + identifier, found, false);
      } catch (Exception error) {
        CatalogSync.warning("Archive.org · " + identifier + " · " + CatalogSync.reason(error));
      }
    }
    data.put("archiveCursor", (cursor + count) % Math.max(1, identifiers.length()));
    return added;
  }
}
