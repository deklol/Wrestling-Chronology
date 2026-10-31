package uk.local.wrestlingchronology;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class WorldwideTest {
  private JSONObject read(String file) throws Exception {
    return new JSONObject(new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8));
  }

  private JSONObject data() throws Exception {
    JSONObject data = read("src/main/assets/libraries/wcw.json");
    SourceConfig.apply(data, read("../sources/wcw.json"));
    return data;
  }

  private Set<String> links(JSONObject data) throws Exception {
    Set<String> result = new HashSet<>();
    JSONArray rows = data.getJSONArray("episodes");
    for (int i = 0; i < rows.length(); i++) {
      JSONArray sources = rows.getJSONObject(i).getJSONArray("sources");
      for (int j = 0; j < sources.length(); j++)
        result.add(Catalog.sourceKey(sources.getJSONObject(j)));
    }
    return result;
  }

  @Test
  public void fullWorldwidesSurviveMetadataRefreshAndAreBrowseableWithExtrasOff() throws Exception {
    List<Catalog.Episode> saved = Catalog.episodes;
    JSONObject old = Catalog.raw;
    try {
      JSONObject data = data();
      JSONArray rows = data.getJSONArray("episodes");
      int expectedWorldwides = 0;
      for (int i = 0; i < rows.length(); i++) {
        JSONObject row = rows.getJSONObject(i);
        if (!row.getString("show").equals("Worldwide")) continue;
        expectedWorldwides++;
        JSONArray sources = row.getJSONArray("sources");
        for (int j = 0; j < sources.length(); j++) {
          sources.getJSONObject(j).remove("programme");
          sources.getJSONObject(j).put("duration", 2440);
        }
      }
      Catalog.replace(data);
      assertTrue(expectedWorldwides >= 294);
      assertEquals(expectedWorldwides, Catalog.episodes.stream().filter(e -> e.show.equals("Worldwide")).count());
      assertEquals(51, Catalog.forYear(1999, "Worldwide", false).size());
      assertEquals(
          0,
          Catalog.forYear(1999, "All", false).stream()
              .filter(e -> e.show.equals("Worldwide"))
              .count());
      for (Catalog.Episode episode : Catalog.forYear(1999, "Worldwide", false))
        assertEquals("Worldwide", episode.sources.get(0).getString("programme"));
    } finally {
      Catalog.episodes = saved;
      Catalog.raw = old;
    }
  }

  @Test
  public void explicitPartialFilesAndSegmentsAreExcludedButIncompleteYearLabelsAreSafe()
      throws Exception {
    for (String name :
        new String[] {
          "WCW.Worldwide.08.04.90_INC.mp4",
          "Worldwide partial.mp4",
          "Worldwide incomplete.mp4",
          "Worldwide segment.mp4",
          "Worldwide clips.mp4"
        })
      assertFalse(
          name,
          SourcePolicy.eligible(
              new JSONObject()
                  .put("type", "archive")
                  .put("filename", name)
                  .put("programme", "Worldwide")
                  .put("duration", 2500)));
    assertTrue(
        SourcePolicy.eligible(
            new JSONObject()
                .put("type", "archive")
                .put(
                    "url",
                    "https://archive.org/download/wcw-worldwide-95-incomplete/Worldwide.01.07.95.mp4")
                .put("programme", "Worldwide")
                .put("duration", 2500)));
    assertFalse(
        SourcePolicy.eligible(
            new JSONObject().put("type", "archive").put("programme", "PPV").put("duration", 2440)));
  }

  @Test
  public void actual1999MetadataProduces51CorrectDatesAndARepeatScanAddsNothing() throws Exception {
    JSONObject data = data();
    Set<String> before = links(data);
    JSONObject metadata = read("src/test/resources/worldwide-1999.json");
    String id = "wcw.-worldwide.-complete_202406";
    ArchiveSync.merge(data, id, metadata);
    assertTrue(links(data).containsAll(before));
    Set<String> dates = new HashSet<>();
    JSONArray rows = data.getJSONArray("episodes");
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      if (row.getString("show").equals("Worldwide") && row.getInt("year") == 1999)
        dates.add(row.getString("date"));
    }
    assertEquals(51, dates.size());
    for (String wrong : new String[] {"1999-04-09", "1999-06-11", "1999-09-10", "1999-11-12"})
      assertFalse(dates.contains(wrong));
    for (String correct : new String[] {"1999-09-04", "1999-11-06", "1999-10-09", "1999-12-11"})
      assertTrue(dates.contains(correct));
    Set<String> once = links(data);
    assertEquals(0, ArchiveSync.merge(data, id, metadata));
    assertEquals(once, links(data));
  }

  @Test
  public void correctedDatesRetainLinksAndRecoverProgressEvenWhenOldRowWasAlreadyLost()
      throws Exception {
    JSONObject data = data();
    JSONArray legacy =
        new JSONArray(
            new String(
                Files.readAllBytes(Paths.get("src/test/resources/worldwide-misdated.json")),
                StandardCharsets.UTF_8));
    for (int i = 0; i < legacy.length(); i++)
      data.getJSONArray("episodes").put(legacy.getJSONObject(i));
    Set<String> before = links(data);
    Map<String, String> moved = ArchiveIdentity.normalize(data);
    assertEquals(4, moved.size());
    assertEquals(before, links(data));
    assertTrue(ArchiveIdentity.normalize(data).isEmpty());
    for (Map.Entry<String, String> move : moved.entrySet())
      assertEquals(move.getValue(), ArchiveIdentity.configuredMoves(data).get(move.getKey()));
    Map<String, Object> saved = new HashMap<>();
    saved.put("pos:1999-04-09:worldwide", 90000L);
    saved.put("last", "1999-04-09:worldwide");
    Map<String, Object> changes =
        ArchiveIdentity.historyChanges(saved, ArchiveIdentity.configuredMoves(data));
    assertEquals(90000L, changes.get("pos:1999-09-04:worldwide"));
    assertEquals("1999-09-04:worldwide", changes.get("last"));
    saved.put("pos:1999-09-04:worldwide", 180000L);
    assertFalse(
        ArchiveIdentity.historyChanges(saved, ArchiveIdentity.configuredMoves(data))
            .containsKey("pos:1999-09-04:worldwide"));
  }
}
