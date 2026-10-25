package uk.local.wrestlingchronology;

import static org.junit.Assert.*;

import java.util.*;
import org.json.JSONObject;
import org.junit.Test;

public class NavigationHistoryTest {
  private Catalog.Episode episode(String id, String date) throws Exception {
    return new Catalog.Episode(
        new JSONObject()
            .put("id", id)
            .put("date", date)
            .put("year", Integer.parseInt(date.substring(0, 4)))
            .put("show", "Raw")
            .put("title", "Full show"));
  }

  @Test
  public void failedNewSelectionDoesNotHideAnUnfinishedShow() throws Exception {
    Catalog.Episode unfinished = episode("unfinished", "2000-01-03");
    Catalog.Episode failed = episode("failed", "2000-01-10");
    Map<String, Object> saved = new HashMap<>();
    saved.put("last", failed.id);
    saved.put("pos:" + unfinished.id, 1800000L);
    assertSame(unfinished, Catalog.continueWatching(Arrays.asList(unfinished, failed), saved));
  }

  @Test
  public void mostRecentlyPlayedUnfinishedEpisodeWinsAcrossYears() throws Exception {
    Catalog.Episode earlier = episode("earlier", "1995-01-02");
    Catalog.Episode later = episode("later", "2005-01-03");
    Map<String, Object> saved = new HashMap<>();
    saved.put("pos:earlier", 500000L);
    saved.put("played:earlier", 200L);
    saved.put("pos:later", 900000L);
    saved.put("played:later", 100L);
    assertSame(earlier, Catalog.continueWatching(Arrays.asList(earlier, later), saved));
    saved.put("watched:earlier", true);
    assertSame(later, Catalog.continueWatching(Arrays.asList(earlier, later), saved));
  }

  @Test
  public void legacyLastEntryIsPreferredWithoutErasingOlderHistory() throws Exception {
    Catalog.Episode a = episode("a", "1997-01-06"), b = episode("b", "1997-01-13");
    Map<String, Object> saved = new HashMap<>();
    saved.put("pos:a", 1000L);
    saved.put("pos:b", 2000L);
    saved.put("last", b.id);
    assertSame(b, Catalog.continueWatching(Arrays.asList(a, b), saved));
    assertEquals(3, saved.size());
  }

  @Test
  public void multipartShowAtTheStartOfALaterPartCanBeContinued() throws Exception {
    Catalog.Episode item = episode("multipart", "2001-01-01");
    Map<String, Object> saved = new HashMap<>();
    saved.put("pos:multipart", 0L);
    saved.put("part:multipart", 1);
    assertSame(item, Catalog.continueWatching(Collections.singletonList(item), saved));
    saved.put("watched:multipart", true);
    assertNull(Catalog.continueWatching(Collections.singletonList(item), saved));
  }

  @Test
  public void anotherCompanyAndUnstartedShowsDoNotBecomeContinuePreviews() throws Exception {
    Catalog.Episode item = episode("wwe", "2000-01-03");
    Map<String, Object> saved = new HashMap<>();
    saved.put("last", "ecw");
    saved.put("pos:ecw", 600000L);
    assertNull(Catalog.continueWatching(Collections.singletonList(item), saved));
    assertNull(Catalog.continueWatching(Collections.emptyList(), saved));
  }

  @Test
  public void monthJumpTargetsTheFirstAvailableShowWithoutRemovingOtherMonths() throws Exception {
    List<Catalog.Episode> items =
        Arrays.asList(
            episode("jan", "2000-01-03"),
            episode("feb1", "2000-02-07"),
            episode("feb2", "2000-02-14"),
            episode("dec", "2000-12-25"));
    assertEquals(0, Catalog.firstInMonth(items, 1));
    assertEquals(1, Catalog.firstInMonth(items, 2));
    assertEquals(3, Catalog.firstInMonth(items, 12));
    assertEquals(-1, Catalog.firstInMonth(items, 3));
    assertEquals(-1, Catalog.firstInMonth(items, 0));
    assertEquals(-1, Catalog.firstInMonth(Collections.emptyList(), 1));
    assertEquals(4, items.size());
    assertEquals(0, Catalog.firstInMonth(Collections.singletonList(items.get(2)), 2));
  }
}
