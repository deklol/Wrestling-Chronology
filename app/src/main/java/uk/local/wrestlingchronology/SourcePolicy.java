package uk.local.wrestlingchronology;

import java.util.*;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Full-show eligibility is checked before resolution or file-size preferences. */
final class SourcePolicy {
  private static final Pattern EXTRA =
      Patterns.get(
          "(?i)\\b(?:countdown|pre[ ._-]*shows?|preshows?|highlights?|trailer|preview|sample|"
              + "compilation|clips?|segments?|recap|review|reliving[ ._-]+the[ ._-]+war|"
              + "full[ ._-]+match)\\b");

  static boolean eligible(JSONObject source) {
    if (!"archive".equals(source.optString("type"))) return true;
    // An incomplete year collection can contain complete episodes: inspect the file only.
    String filename = ArchiveIdentity.filename(source).replace('_', ' ');
    if (Patterns.get("(?i)\\b(?:inc|incomplete|partial)\\b").matcher(filename).find())
      return false;
    String name = source.optString("filename") + " " + source.optString("url");
    if (EXTRA.matcher(name.replace("%20", " ").replace('_', ' ')).find()) return false;
    // Multipart recordings must be judged by their combined runtime, not one segment.
    if (source.optJSONArray("urls") != null) return true;
    double duration = source.optDouble("duration", 0);
    return duration <= 0 || duration >= minimumSeconds(source.optString("programme"));
  }

  static int minimumSeconds(String show) {
    if (Arrays.asList("Dark", "Dark: Elevation", "NXT Level Up").contains(show)) return 300;
    if (Arrays.asList("205 Live", "NXT UK", "Main Event", "The Deadly Draw").contains(show)) return 600;
    if (Arrays.asList("NXT", "Rampage", "Battle of the Belts").contains(show)) return 1200;
    if (Arrays.asList("Velocity", "Xplosion", "Metal", "Mania", "Action Zone", "All American Wrestling",
        "All Star Wrestling", "Championship Wrestling", "Livewire", "Shotgun Saturday Night",
        "Wrestling Spotlight", "Wrestling Challenge", "Evolve", "ECW", "Cruiserweight Classic", "Mae Young Classic").contains(show)) return 1200;
    if (show.equals("Heat")
        || show.equals("Hardcore TV")
        || show.equals("ECW on TNN")
        || show.equals("Impact")) return 1200;
    if (show.equals("SmackDown")) return 2400;
    if (show.equals("Raw")) return 2300;
    if (show.equals("Nitro")) return 2400;
    if (show.equals("Worldwide")
        || show.equals("Pro")
        || show.equals("Power Hour")
        || show.equals("Prime")) return 1800;
    if (show.equals("Main Event")) return 1200;
    return 2700;
  }

  static int languagePreference(JSONObject source) {
    if (source.optString("type").equals("youtube"))
      return 0; // Resolver selects English/original audio.
    String language =
        source.optString("audioLanguage", source.optString("language")).toLowerCase(Locale.UK);
    String description =
        (source.optString("filename") + " " + source.optString("label")).replace('_', ' ');
    if (Patterns.get("(?:^|[^a-z])(?:en|eng|english)(?:$|[^a-z])").matcher(language).find()
        || Patterns.get("(?i)\\bEnglish\\b").matcher(description).find()) return 0;
    if (Patterns.get(
                "(?:^|[^a-z])(?:de|deu|ger|german|deutsch|fr|fra|fre|french|es|spa|spanish|ja|jpn|japanese|it|ita|italian|mul)(?:$|[^a-z])")
            .matcher(language)
            .find()
        || Patterns.get(
                "(?i)\\b(?:german|deutsch|french|fran[cç]ais|spanish|espa[nñ]ol|japanese|italian|italiano)\\s*(?:commentary|audio|dub(?:bed)?|version)?\\b")
            .matcher(description)
            .find()) return 2;
    return 1;
  }

  static int preference(JSONObject source) {
    return "archive".equals(source.optString("type"))
            && "Chasing That W".equalsIgnoreCase(source.optString("creator"))
            && source.optDouble("duration", 0) >= 5400
        ? 0
        : 1;
  }
}
