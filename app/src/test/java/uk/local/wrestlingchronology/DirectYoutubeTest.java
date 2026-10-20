package uk.local.wrestlingchronology;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class DirectYoutubeTest {
  JSONObject read(String file) throws Exception {
    return new JSONObject(new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8));
  }

  JSONObject seed() throws Exception {
    return read("src/main/assets/libraries/wcw.json");
  }

  JSONObject config() throws Exception {
    return read("../sources/wcw.json");
  }

  JSONObject metadata(JSONObject entry, JSONObject seed) throws Exception {
    JSONArray rows = seed.getJSONArray("episodes");
    for (int i = 0; i < rows.length(); i++) {
      JSONArray sources = rows.getJSONObject(i).getJSONArray("sources");
      for (int j = 0; j < sources.length(); j++) {
        JSONObject source = sources.getJSONObject(j);
        if (entry.getString("id").equals(source.optString("videoId")))
          return new JSONObject()
              .put("title", source.getString("title"))
              .put("author_url", entry.getString("authorUrl"))
              .put("author_name", "WWE Vault");
      }
    }
    throw new AssertionError("Missing configured source");
  }

  @Test
  public void allSixRequestedUploadsSurviveLoadingAndOnlyTheirVaultIdsAreAllowed()
      throws Exception {
    JSONObject data = seed(), config = config();
    Catalog.normalizeYoutube(data);
    SourceConfig.apply(data, config);
    assertEquals(1950, data.getJSONArray("episodes").length());
    JSONArray videos = data.getJSONArray("youtubeVideos");
    assertEquals(6, videos.length());
    for (int i = 0; i < videos.length(); i++) {
      JSONObject entry = videos.getJSONObject(i);
      assertTrue(DirectYoutube.present(data, entry.getString("id")));
      assertTrue(DirectYoutube.allowed(entry.getString("id"), entry.getString("channelId")));
      assertFalse(DirectYoutube.allowed(entry.getString("id"), "UCbGG2hv4cWmIrBAIhGiCdSg"));
    }
    assertFalse(DirectYoutube.allowed("AAAAAAAAAAA", "UCNSnSemXKDc9UEZ7m31wY9Q"));
  }

  @Test
  public void scansAddFourBroadcastsAndSixSourcesWithoutDuplicatingExistingEvents()
      throws Exception {
    JSONObject original = seed(), data = seed();
    JSONArray rows = data.getJSONArray("episodes");
    int beforeLinks = 0;
    for (int i = rows.length() - 1; i >= 0; i--) {
      JSONArray sources = rows.getJSONObject(i).getJSONArray("sources");
      for (int j = sources.length() - 1; j >= 0; j--)
        if (sources.getJSONObject(j).optBoolean("direct")) sources.remove(j);
      if (sources.length() == 0) rows.remove(i);
      else beforeLinks += sources.length();
    }
    assertEquals(1946, rows.length());
    JSONArray videos = config().getJSONObject("youtube").getJSONArray("videos");
    int added = 0;
    for (int i = 0; i < videos.length(); i++) {
      JSONObject entry = videos.getJSONObject(i);
      added += DirectYoutube.merge(data, entry, metadata(entry, original));
    }
    assertEquals(6, added);
    assertEquals(1950, rows.length());
    int afterLinks = 0, havocs = 0;
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      afterLinks += row.getJSONArray("sources").length();
      if (row.getString("date").equals("1998-10-25") && row.getString("show").equals("PPV")) {
        havocs++;
        assertEquals("1998-10-25:halloweenhavoc", row.getString("id"));
        assertEquals(5, row.getJSONArray("sources").length());
      }
    }
    assertEquals(1, havocs);
    assertEquals(beforeLinks + 6, afterLinks);
    for (int i = 0; i < videos.length(); i++) {
      JSONObject entry = videos.getJSONObject(i);
      assertEquals(0, DirectYoutube.merge(data, entry, metadata(entry, original)));
    }
    assertEquals(1950, rows.length());
  }

  @Test
  public void disablingOrRemovingADirectSourceFiltersItWithoutRemovingOtherCopies()
      throws Exception {
    JSONObject data = seed(), cfg = config();
    JSONArray videos = cfg.getJSONObject("youtube").getJSONArray("videos");
    for (int i = 0; i < videos.length(); i++)
      if (videos.getJSONObject(i).getString("id").equals("kJRzqQwZ7po"))
        videos.getJSONObject(i).put("enabled", false);
    SourceConfig.apply(data, cfg);
    assertFalse(DirectYoutube.present(data, "kJRzqQwZ7po"));
    assertEquals(1950, data.getJSONArray("episodes").length());
    cfg = config();
    videos = cfg.getJSONObject("youtube").getJSONArray("videos");
    for (int i = videos.length() - 1; i >= 0; i--)
      if (videos.getJSONObject(i).getString("id").equals("2mykcQhMIcM")) videos.remove(i);
    data = seed();
    SourceConfig.apply(data, cfg);
    assertFalse(DirectYoutube.present(data, "2mykcQhMIcM"));
    assertEquals(1949, data.getJSONArray("episodes").length());
  }

  @Test
  public void hashesIgnorePresentationNoiseAndMismatchedUploadersCannotAddSources()
      throws Exception {
    JSONObject entry = config().getJSONObject("youtube").getJSONArray("videos").getJSONObject(0);
    JSONObject meta = metadata(entry, seed());
    String hash = DirectYoutube.fingerprint(entry, meta);
    meta.put("html", "different tracking parameters").put("thumbnail_url", "different thumbnail");
    assertEquals(hash, DirectYoutube.fingerprint(entry, meta));
    meta.put("title", meta.getString("title") + " updated");
    assertNotEquals(hash, DirectYoutube.fingerprint(entry, meta));
    meta.put("author_url", "https://www.youtube.com/@UnrelatedChannel");
    JSONObject data = seed();
    String before = data.getJSONArray("episodes").toString();
    try {
      DirectYoutube.merge(data, entry, meta);
      fail("Wrong uploader accepted");
    } catch (java.io.IOException expected) {
      assertTrue(expected.getMessage().contains("uploader"));
    }
    assertEquals(before, data.getJSONArray("episodes").toString());
  }
}
