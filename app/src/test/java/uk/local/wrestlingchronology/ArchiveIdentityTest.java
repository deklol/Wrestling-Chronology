package uk.local.wrestlingchronology;

import static org.junit.Assert.*;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class ArchiveIdentityTest {
  private JSONObject read(String path) throws Exception {
    return new JSONObject(
        new String(Files.readAllBytes(Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8));
  }

  private JSONObject data() throws Exception {
    JSONObject data = read("src/main/assets/libraries/wcw.json");
    SourceConfig.apply(data, read("../sources/wcw.json"));
    return data;
  }

  private JSONObject row(JSONObject data, String date, String show) throws Exception {
    JSONArray rows = data.getJSONArray("episodes");
    for (int i = 0; i < rows.length(); i++)
      if (date.equals(rows.getJSONObject(i).optString("date"))
          && show.equals(rows.getJSONObject(i).optString("show"))) return rows.getJSONObject(i);
    return null;
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

  private void addWrong(JSONObject data, String day) throws Exception {
    String date = "2000-01-" + day;
    data.getJSONArray("episodes")
        .put(
            new JSONObject()
                .put("id", date + ":nitro")
                .put("show", "Nitro")
                .put("title", "Nitro")
                .put("date", date)
                .put("year", 2000)
                .put("discoveredAt", 123L)
                .put(
                    "sources",
                    new JSONArray()
                        .put(
                            new JSONObject()
                                .put("type", "archive")
                                .put("filename", "01 " + day + " 2000 T.mp4")
                                .put(
                                    "url",
                                    "https://archive.org/download/jan-3-2000-dec-27-2000/01%20"
                                        + day
                                        + "%202000%20T.mp4"))));
  }

  @Test
  public void cachedDuplicatesMoveWithoutLosingAnyPlayableSource() throws Exception {
    JSONObject data = data();
    addWrong(data, "19");
    addWrong(data, "26");
    Set<String> before = links(data);
    Map<String, String> moves = ArchiveIdentity.normalize(data);
    assertEquals(before, links(data));
    assertNull(row(data, "2000-01-19", "Nitro"));
    assertNull(row(data, "2000-01-26", "Nitro"));
    assertNotNull(row(data, "2000-01-19", "Thunder"));
    assertNotNull(row(data, "2000-01-26", "Thunder"));
    assertEquals("2000-01-19:thunder", moves.get("2000-01-19:nitro"));
    String once = data.toString();
    assertTrue(ArchiveIdentity.normalize(data).isEmpty());
    assertEquals(once, data.toString());
  }

  @Test
  public void bundledEpisodesAndSourceAssignmentsAreUnchanged() throws Exception {
    JSONObject data = data();
    String before = data.getJSONArray("episodes").toString();
    assertTrue(ArchiveIdentity.normalize(data).isEmpty());
    assertEquals(before, data.getJSONArray("episodes").toString());
  }

  @Test
  public void realMixedCollectionsKeepEveryExistingLinkAndSeparateBothShows() throws Exception {
    JSONObject data = data(), fixtures = read("src/test/resources/mixed-archive-items.json");
    Set<String> before = links(data);
    Set<String> ids = new HashSet<>();
    JSONArray rows = data.getJSONArray("episodes");
    for (int i = 0; i < rows.length(); i++) ids.add(rows.getJSONObject(i).getString("id"));
    for (Iterator<String> it = fixtures.keys(); it.hasNext(); ) {
      String id = it.next();
      ArchiveSync.merge(data, id, fixtures.getJSONObject(id));
    }
    ArchiveIdentity.normalize(data);
    assertTrue(links(data).containsAll(before));
    Set<String> afterIds = new HashSet<>();
    for (int i = 0; i < rows.length(); i++) afterIds.add(rows.getJSONObject(i).getString("id"));
    assertTrue(afterIds.containsAll(ids));
    for (Iterator<String> it = fixtures.keys(); it.hasNext(); ) {
      String id = it.next();
      JSONArray files = fixtures.getJSONObject(id).getJSONArray("files");
      for (int i = 0; i < files.length(); i++) {
        JSONObject file = files.getJSONObject(i);
        String name = file.getString("name"), show = ArchiveSync.family(name);
        if (!"Nitro".equals(show) && !"Thunder".equals(show)) continue;
        if (file.optDouble("length") < 2700) continue;
        String date = ArchiveSync.fileDate(name, data, id);
        if (date == null) continue;
        JSONObject episode = row(data, date, show);
        assertNotNull(id + "/" + name, episode);
        JSONArray sources = episode.getJSONArray("sources");
        boolean found = false;
        for (int j = 0; j < sources.length(); j++)
          found |=
              name.equals(ArchiveIdentity.filename(sources.getJSONObject(j)))
                  && id.equals(ArchiveSync.collection(sources.getJSONObject(j).optString("url")));
        assertTrue(id + "/" + name + " must be under " + show + " " + date, found);
      }
    }
    for (String day : new String[] {"19", "26"}) {
      assertNull(row(data, "2000-01-" + day, "Nitro"));
      assertNotNull(row(data, "2000-01-" + day, "Thunder"));
    }
    Set<String> after = links(data);
    int count = rows.length();
    for (Iterator<String> it = fixtures.keys(); it.hasNext(); ) {
      String id = it.next();
      assertEquals(0, ArchiveSync.merge(data, id, fixtures.getJSONObject(id)));
    }
    assertEquals(count, rows.length());
    assertEquals(after, links(data));
    System.out.println(
        "Mixed collection audit: original episodes="
            + ids.size()
            + ", final episodes="
            + count
            + ", original links="
            + before.size()
            + ", final links="
            + after.size());
  }

  @Test
  public void markersAndExplicitDateConventionOverrideHintsWithoutWeekdayGuessing()
      throws Exception {
    JSONObject data = data();
    assertEquals("Thunder", ArchiveSync.family("01 19 2000 T.ia.mp4"));
    assertEquals("Nitro", ArchiveSync.family("01 03 2000 N.mp4"));
    assertNull(ArchiveSync.family("WCW Ortin Rednuht FULL YEAR"));
    assertEquals(
        "2000-01-06", ArchiveSync.fileDate("01 06 2000 T.mp4", data, "jan-3-2000-dec-27-2000"));
    assertNull(ArchiveSync.fileDate("01 06 2000 T.mp4", data, "unknown-collection"));
    assertNull(ArchiveSync.family("WCW SuperBrawl IV Thundercage"));
  }

  @Test
  public void historyMovesAndExistingCorrectProgressIsNeverOverwritten() {
    Map<String, Object> saved = new HashMap<>();
    saved.put("pos:2000-01-19:nitro", 123000L);
    saved.put("watched:2000-01-19:nitro", false);
    saved.put("choice:2000-01-19:nitro", "source-key");
    saved.put("last", "2000-01-19:nitro");
    Map<String, String> moves = Collections.singletonMap("2000-01-19:nitro", "2000-01-19:thunder");
    Map<String, Object> changes = ArchiveIdentity.historyChanges(saved, moves);
    assertEquals(123000L, changes.get("pos:2000-01-19:thunder"));
    assertEquals("source-key", changes.get("choice:2000-01-19:thunder"));
    assertEquals("2000-01-19:thunder", changes.get("last"));
    saved.put("pos:2000-01-19:thunder", 456000L);
    assertFalse(ArchiveIdentity.historyChanges(saved, moves).containsKey("pos:2000-01-19:thunder"));
  }
}
