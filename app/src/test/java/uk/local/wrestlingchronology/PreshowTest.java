package uk.local.wrestlingchronology;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PreshowTest {
  @Test public void remotePackMergeAlsoKeepsBothBroadcasts() throws Exception {
    JSONObject config = new JSONObject().put("archive", new JSONObject()).put("youtube", new JSONObject());
    JSONObject old = new JSONObject().put("sources", config).put("catalogue", library(episode("main", false, "111111")));
    JSONObject fresh = new JSONObject().put("sources", config).put("catalogue",
        library(episode("pre", true, "222222"), episode("main", false, "333333")));
    JSONObject result = LibraryPack.merge(old, fresh);
    JSONArray rows = result.getJSONObject("catalogue").getJSONArray("episodes");
    assertEquals(2, rows.length());
    assertEquals("main", rows.getJSONObject(0).getString("id"));
    assertEquals(2, rows.getJSONObject(0).getJSONArray("sources").length());
    assertEquals("pre", rows.getJSONObject(1).getString("id"));
    assertEquals(1, rows.getJSONObject(1).getJSONArray("sources").length());
  }
  @Test public void officialShortPreshowSurvivesRefreshWithoutReplacingMainEvent() throws Exception {
    Company previous = Company.current;
    try {
      Company.current = Company.AEW;
      JSONObject entry = new JSONObject().put("id", "mNcrhHkut4k")
          .put("channelId", "UCFN4JkGP_bVhAdBsoV9xftA").put("authorUrl", "https://www.youtube.com/@AEW")
          .put("date", "2026-10-18").put("show", "PPV").put("eventPart", "preshow")
          .put("title", "WrestleDream — Buy In").put("name", "The Buy In").put("duration", 1736);
      JSONObject metadata = new JSONObject().put("author_url", "https://www.youtube.com/@AEW")
          .put("title", "The Buy In: AEW WrestleDream");
      JSONObject data = library(episode("aew:main", false, "111111"));
      assertEquals(1, DirectYoutube.merge(data, entry, metadata));
      assertEquals(0, DirectYoutube.merge(data, entry, metadata));
      assertEquals(2, data.getJSONArray("episodes").length());
      assertEquals(1, data.getJSONArray("episodes").getJSONObject(0).getJSONArray("sources").length());
      assertEquals(-1, data.getJSONArray("episodes").getJSONObject(1).getInt("eventOrder"));
    } finally { Company.current = previous; }
  }

  private JSONObject episode(String id, boolean preshow, String video) throws Exception {
    return new JSONObject()
        .put("id", id).put("date", "2026-10-18").put("year", 2026)
        .put("show", "PPV").put("title", preshow ? "WrestleDream — Zero Hour" : "WrestleDream")
        .put("eventOrder", preshow ? -1 : 0)
        .put("sources", new JSONArray().put(new JSONObject().put("type", "okru").put("videoId", video)));
  }

  private JSONObject library(JSONObject... episodes) throws Exception {
    JSONArray rows = new JSONArray();
    for (JSONObject episode : episodes) rows.put(episode);
    return new JSONObject().put("episodes", rows).put("eventCalendar", new JSONArray())
        .put("playlists", new JSONArray()).put("archiveItems", new JSONArray());
  }

  @Test public void cacheKeepsPreshowSeparateAndMergesAlternateRecordings() throws Exception {
    JSONObject cache = library(episode("aew:main", false, "111111"));
    JSONObject seed = library(episode("aew:main", false, "222222"),
        episode("aew:preshow", true, "333333"));
    Catalog.mergeSeed(cache, seed);
    Catalog.mergeSeed(cache, seed);
    JSONArray rows = cache.getJSONArray("episodes");
    assertEquals(2, rows.length());
    assertEquals(2, rows.getJSONObject(0).getJSONArray("sources").length());
    assertEquals("aew:preshow", rows.getJSONObject(1).getString("id"));
    assertEquals(1, rows.getJSONObject(1).getJSONArray("sources").length());
  }

  @Test public void ppvQueuePlacesPreshowBeforeMainEvent() throws Exception {
    Company previous = Company.current;
    JSONObject old = Catalog.raw;
    try {
      Company.current = Company.AEW;
      Catalog.replace(library(episode("aew:main", false, "111111"),
          episode("aew:preshow", true, "222222")));
      assertEquals(2, Catalog.episodes.size());
      assertEquals("aew:preshow", Catalog.episodes.get(0).id);
      assertEquals("PPV", Catalog.episodes.get(0).show);
      assertEquals("aew:main", Catalog.episodes.get(1).id);
    } finally {
      Company.current = previous;
      if (old != null) Catalog.replace(old);
    }
  }
}
