package uk.local.wrestlingchronology;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class Pre1997Test {
  @Test
  public void refreshKeepsWcwNewJapanJointEventsButRejectsUnrelatedNJPW() throws Exception {
    JSONObject data = seed();
    data.put("archiveHints", new JSONObject().put("joint-event", "PPV"));
    JSONObject files =
        new JSONObject()
            .put("metadata", new JSONObject().put("title", "WCW joint event"))
            .put(
                "files",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("name", "WCW.NJPW.Supershow.1991-03-21.mp4")
                            .put("length", 11000)
                            .put("size", 2000000000))
                    .put(
                        new JSONObject()
                            .put("name", "NJPW.1991-03-21.mp4")
                            .put("length", 11000)
                            .put("size", 2000000000)));
    ArchiveSync.merge(data, "joint-event", files);
    JSONObject event = null;
    for (int i = 0; i < data.getJSONArray("episodes").length(); i++) {
      JSONObject row = data.getJSONArray("episodes").getJSONObject(i);
      if (row.getString("date").equals("1991-03-21") && row.getString("show").equals("PPV"))
        event = row;
    }
    assertNotNull(event);
    assertTrue(
        event.getJSONArray("sources").toString().contains("WCW.NJPW.Supershow.1991-03-21.mp4"));
    assertFalse(event.getJSONArray("sources").toString().contains("/NJPW.1991-03-21.mp4"));
  }

  private JSONObject seed() throws Exception {
    return new JSONObject(
        new String(
            Files.readAllBytes(Paths.get("src/main/assets/libraries/wcw.json")),
            StandardCharsets.UTF_8));
  }

  @Test
  public void expansionAndEveryPost1997SourceArePreserved() throws Exception {
    JSONObject data = seed();
    JSONArray baseline =
        new JSONArray(
            new String(
                Files.readAllBytes(Paths.get("src/test/resources/post1997-baseline.json")),
                StandardCharsets.UTF_8));
    Map<String, JSONObject> rows = new HashMap<>();
    int pre = 0, nitro = 0, clash = 0;
    for (int i = 0; i < data.getJSONArray("episodes").length(); i++) {
      JSONObject row = data.getJSONArray("episodes").getJSONObject(i);
      rows.put(row.getString("id"), row);
      if (row.getInt("year") < 1997) {
        pre++;
        if (row.getString("show").equals("Nitro")) nitro++;
        if (row.getString("show").equals("Clash of the Champions")) clash++;
      }
    }
    assertEquals(1278, pre);
    assertEquals(68, nitro);
    assertEquals(33, clash);
    for (int i = 0; i < baseline.length(); i++) {
      JSONObject old = baseline.getJSONObject(i);
      if (old.getString("id").equals("2001-01-14:clashofthechampions")) {
        JSONObject correct = rows.get("1990-11-20:clashofthechampions");
        assertNotNull(correct);
        assertTrue(correct.getJSONArray("sources").toString().contains("DAOp9Bgp030"));
        assertNull(rows.get(old.getString("id")));
        continue;
      }
      String corrected =
          data.getJSONObject("historyMigrations")
              .optString(old.getString("id"), old.getString("id"));
      JSONObject row = rows.get(corrected);
      assertNotNull(row);
      assertEquals(corrected.substring(0, 10), row.getString("date"));
      assertEquals(old.getString("show"), row.getString("show"));
      JSONArray expected = old.getJSONArray("sources"), actual = row.getJSONArray("sources");
      assertTrue(actual.length() >= expected.length());
      for (int j = 0; j < expected.length(); j++) {
        JSONObject source = actual.getJSONObject(j);
        assertEquals(
            expected.get(j).toString(),
            source.has("url")
                ? source.getString("url")
                : source.has("videoId")
                    ? source.getString("videoId")
                    : source.getJSONArray("urls").toString());
      }
    }
  }

  @Test
  public void earlyYearsIncludeAllProgrammesAndRemainChronological() throws Exception {
    List<Catalog.Episode> saved = Catalog.episodes;
    JSONObject old = Catalog.raw;
    try {
      Catalog.replace(seed());
      ArrayList<Catalog.Episode> year = Catalog.forYear(1996, "All", false);
      assertEquals(155, year.size());
      Set<String> shows = new HashSet<>();
      String last = "";
      for (Catalog.Episode row : year) {
        shows.add(row.show);
        assertTrue(last.compareTo(row.date) <= 0);
        last = row.date;
      }
      assertTrue(shows.containsAll(Arrays.asList("Nitro", "Saturday Night", "PPV", "Pro")));
      assertEquals(135, Catalog.forYear(1995, "All", false).size());
      assertTrue(
          Catalog.forYear(1990, "All", false).stream().anyMatch(e -> e.show.equals("Power Hour")));
      assertTrue(
          Catalog.forYear(1990, "All", false).stream().anyMatch(e -> e.show.equals("Main Event")));
      assertEquals(
          "World Championship Wrestling",
          Catalog.forYear(1985, "Saturday Night", false).get(0).heading());
      assertTrue(Catalog.years().containsAll(Arrays.asList(1983, 1985, 1996, 1997, 2001)));
    } finally {
      Catalog.episodes = saved;
      Catalog.raw = old;
    }
  }

  @Test
  public void historicEpisodesAndNumberedClashesAreRecognised() throws Exception {
    JSONObject data = seed();
    assertEquals(
        "Saturday Night",
        CatalogSync.show("FULL EPISODE: Dusty Rhodes calls out Flair: WCW, Nov. 2, 1985"));
    assertEquals(
        "1985-11-02",
        CatalogSync.date("FULL EPISODE: Dusty Rhodes calls out Flair: WCW, Nov. 2, 1985", data));
    assertEquals(
        "1988-03-27",
        CatalogSync.date("FULL EVENT: Clash of the Champions I | Sting vs. Flair", data));
    assertEquals(
        "1995-01-25",
        CatalogSync.date("FULL EPISODE: Clash of the Champions 30 | Hogan & Savage", data));
    assertEquals("1992-06-16", CatalogSync.date("FULL EVENT: Clash of the Champions XIX", data));
    assertEquals(
        "Clash of the Champions",
        ArchiveSync.family("11.20 - Clash of the Champions XIII (Thanksgiving Thunder).mp4"));
    assertNull(CatalogSync.validDate(1982, 12, 31));
  }

  @Test
  public void wronglyDatedClashMergesWithoutLosingItsSourceOrHistory() throws Exception {
    JSONObject data = seed();
    JSONObject wrong =
        new JSONObject()
            .put("id", "2001-01-14:clashofthechampions")
            .put("date", "2001-01-14")
            .put("year", 2001)
            .put("show", "Clash of the Champions")
            .put("title", "Clash of the Champions")
            .put(
                "sources",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("type", "youtube")
                            .put("videoId", "DAOp9Bgp030")
                            .put(
                                "title",
                                "FULL EVENT: Clash of the Champions 13 | Ric Flair vs. Butch Reed;"
                                    + " Sid Vicious in action")));
    data.getJSONArray("episodes").put(wrong);
    Map<String, String> moves = Catalog.normalizeYoutube(data);
    assertEquals("1990-11-20:clashofthechampions", moves.get(wrong.getString("id")));
    Map<String, Object> saved = new HashMap<>();
    saved.put("pos:" + wrong.getString("id"), 123000L);
    saved.put("last", wrong.getString("id"));
    Map<String, Object> changes = ArchiveIdentity.historyChanges(saved, moves);
    assertEquals(123000L, changes.get("pos:1990-11-20:clashofthechampions"));
    assertEquals("1990-11-20:clashofthechampions", changes.get("last"));
    assertEquals(1950, data.getJSONArray("episodes").length());
    assertTrue(Catalog.normalizeYoutube(data).isEmpty());
  }

  @Test
  public void runtimeRulesKeepShorterFullWeeklyFormats() throws Exception {
    assertTrue(
        SourcePolicy.eligible(
            new JSONObject()
                .put("type", "archive")
                .put("programme", "Nitro")
                .put("duration", 2669)));
    assertTrue(
        SourcePolicy.eligible(
            new JSONObject().put("type", "archive").put("programme", "Pro").put("duration", 2400)));
    assertFalse(
        SourcePolicy.eligible(
            new JSONObject()
                .put("type", "archive")
                .put("programme", "Nitro")
                .put("duration", 600)));
  }

  @Test
  public void englishWinsBeforePictureResolution() throws Exception {
    JSONObject row =
        new JSONObject()
            .put("id", "2000-11-16:test")
            .put("title", "Test")
            .put("year", 2000)
            .put("date", "2000-11-16")
            .put("show", "PPV");
    JSONObject german =
        new JSONObject()
            .put("type", "archive")
            .put("filename", "German Commentary.mp4")
            .put("label", "1080p");
    JSONObject english =
        new JSONObject()
            .put("type", "archive")
            .put("filename", "English Commentary.mp4")
            .put("label", "480p");
    JSONObject unknown =
        new JSONObject().put("type", "archive").put("filename", "event.mp4").put("label", "720p");
    row.put("sources", new JSONArray().put(german).put(unknown).put(english));
    ArrayList<JSONObject> options = Resolver.options(new Catalog.Episode(row), 2, null, 720);
    assertSame(english, options.get(0));
    assertSame(german, options.get(2));
    assertEquals(0, SourcePolicy.languagePreference(new JSONObject().put("language", "eng")));
    assertEquals(2, SourcePolicy.languagePreference(new JSONObject().put("audioLanguage", "de")));
  }
}
