package uk.local.wrestlingchronology;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.json.*;
import org.junit.Test;

public class PpvSourceTest {
  private JSONObject seed() throws Exception {
    return new JSONObject(
        new String(
            Files.readAllBytes(Paths.get("src/main/assets/libraries/wcw.json")),
            java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  public void rejectsPreshowAndReviewButKeepsMultipart() throws Exception {
    for (String name :
        new String[] {
          "WCW Souled Out 2000 Countdown Show.ia.mp4",
          "WCW SuperBrawl 2000 - The Reliving The War PPV.mp4",
          "WCW Fall Brawl 1997 pre-show.mp4"
        }) {
      assertFalse(
          SourcePolicy.eligible(
              new JSONObject().put("type", "archive").put("filename", name).put("duration", 6000)));
    }
    assertFalse(
        SourcePolicy.eligible(new JSONObject().put("type", "archive").put("duration", 1800)));
    assertTrue(
        SourcePolicy.eligible(
            new JSONObject()
                .put("type", "archive")
                .put("duration", 1800)
                .put("urls", new JSONArray().put("part1").put("part2"))));
  }

  @Test
  public void auditedCopiesWinAtDefault720pForAll51Events() throws Exception {
    JSONArray rows = seed().getJSONArray("episodes");
    int checked = 0;
    for (int i = 0; i < rows.length(); i++) {
      Catalog.Episode episode = new Catalog.Episode(rows.getJSONObject(i));
      if (episode.year < 1997) continue;
      boolean curated = false;
      for (int j = 0; j < episode.sources.size(); j++)
        curated |= SourcePolicy.preference(episode.sources.get(j)) == 0;
      if (!curated) continue;
      assertEquals(
          episode.title,
          "Chasing That W",
          Resolver.options(episode, 2, null, 720).get(0).optString("creator"));
      checked++;
    }
    assertEquals(51, checked);
  }

  @Test
  public void existingCacheReceivesMetadataAndLosesInvalidOptions() throws Exception {
    JSONObject seed = seed(), cache = new JSONObject(seed.toString());
    JSONArray rows = cache.getJSONArray("episodes");
    JSONObject target = null;
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      if (row.getString("id").equals("2000-01-16:souledout")) target = row;
      JSONArray sources = row.getJSONArray("sources");
      for (int j = 0; j < sources.length(); j++) {
        sources.getJSONObject(j).remove("creator");
        sources.getJSONObject(j).remove("duration");
      }
    }
    assertNotNull(target);
    target
        .getJSONArray("sources")
        .put(
            new JSONObject()
                .put("type", "archive")
                .put("filename", "WCW Souled Out 2000 Countdown Show.ia.mp4"));
    java.util.List<Catalog.Episode> oldEpisodes = Catalog.episodes;
    JSONObject oldRaw = Catalog.raw;
    try {
      Catalog.mergeSeed(cache, seed);
      Catalog.replace(cache);
      Catalog.Episode event = Catalog.find("2000-01-16:souledout");
      assertEquals(4, event.sources.size());
      JSONObject chosen = Resolver.options(event, 2, null, 720).get(0);
      assertEquals("Chasing That W", chosen.getString("creator"));
      assertEquals(9759.17, chosen.getDouble("duration"), .01);
    } finally {
      Catalog.episodes = oldEpisodes;
      Catalog.raw = oldRaw;
    }
  }

  @Test
  public void refreshCannotReintroduceLongPreshowsOrReviews() throws Exception {
    JSONObject data = seed();
    JSONObject item =
        new JSONObject(
            "{metadata:{title:'WCW PPV'},files:["
                + "{name:'2000-01-16 Countdown.mp4',length:6000},"
                + "{name:'2000-02-20 Reliving The War.mp4',length:6000}]}");
    assertEquals(0, ArchiveSync.merge(data, "test-ppv", item));
  }
}
