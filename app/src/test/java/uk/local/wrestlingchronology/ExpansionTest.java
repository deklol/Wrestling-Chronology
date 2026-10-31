package uk.local.wrestlingchronology;
import static org.junit.Assert.*;
import org.junit.Test;
import org.json.*;
import java.nio.file.*;
import java.util.*;
public class ExpansionTest {
  @Test public void weeklyFiltersExcludeStandaloneEventsButKeepWeeklyPpvSeries() {
    assertFalse(Catalog.weeklyShow("PPV"));
    assertFalse(Catalog.weeklyShow("Specials"));
    assertFalse(Catalog.weeklyShow("Live event"));
    assertFalse(Catalog.weeklyShow("One Night Only"));
    assertTrue(Catalog.weeklyShow("Weekly PPV"));
    assertTrue(Catalog.weeklyShow("HonorClub"));
    assertTrue(Catalog.weeklyShow("Saturday Night"));
  }
  JSONObject read(String p) throws Exception { return new JSONObject(new String(Files.readAllBytes(Paths.get(p)), java.nio.charset.StandardCharsets.UTF_8)); }
  @Test public void everyCompanyHasValidUniqueDatedBroadcasts() throws Exception {
    Company saved = Company.current;
    try { for (Company co : Company.ALL) {
      Company.current = co;
      JSONObject data = read("src/main/assets/libraries/" + co.id + ".json");
      JSONObject sources = read("../sources/" + co.id + ".json");
      LibraryPack.validate(new JSONObject().put("schemaVersion",1).put("company",co.id)
          .put("revision",1).put("catalogue",data).put("sources",sources),co);
      SourceConfig.apply(data,sources);
      Set<String> keys = new HashSet<>();
      for (int i=0;i<data.getJSONArray("episodes").length();i++) {
        JSONObject e=data.getJSONArray("episodes").getJSONObject(i);
        String identity = e.getString("show") + ":" + e.getString("date");
        // ROH's Do or Die cards and evening events can share a date. They are
        // separate complete cards, not duplicate copies of one weekly episode.
        if (co == Company.ROH && e.getString("show").equals("PPV"))
          identity += ":" + e.getString("title");
        assertTrue(e.getString("id"), keys.add(identity));
        assertTrue(e.getString("date").compareTo(co.firstDate) >= 0);
        assertTrue(e.getString("date").compareTo("2026-10-29") <= 0);
        assertFalse(e.getString("show").contains("Superstars"));
        assertFalse(e.getString("show").contains("Saturday Morning Slam"));
      }
      Catalog.replace(data);
      assertFalse(Catalog.episodes.isEmpty());
      assertTrue(Catalog.years().last() <= 2026);
    }} finally { Company.current=saved; }
    assertEquals("2028-12-31",Company.WWE.lastDate);
    assertEquals("2028-12-31",Company.TNA.lastDate);
    assertEquals("2028-12-31",Company.AEW.lastDate);
  }
  @Test public void hidingHeatFiltersChronologyAndKeepsExplicitShowAccess() throws Exception {
    Company saved=Company.current; List<Catalog.Episode> old=Catalog.episodes;
    Set<String> hidden=Catalog.hiddenShows; JSONObject raw=Catalog.raw;
    try { Company.current=Company.WWE;
      Catalog.replace(read("src/main/assets/libraries/wwe.json"));
      Catalog.hiddenShows=Collections.singleton("Heat");
      ArrayList<Catalog.Episode> queue=Catalog.forYear(2006,"All",true);
      assertFalse(queue.isEmpty());
      assertTrue(queue.stream().noneMatch(e -> e.show.equals("Heat")));
      assertFalse(Catalog.forYear(2006,"Heat",true).isEmpty());
      String last=""; for(Catalog.Episode e:queue){assertTrue(last.compareTo(e.date)<=0);last=e.date;}
    } finally {Company.current=saved;Catalog.episodes=old;Catalog.raw=raw;Catalog.hiddenShows=hidden;}
  }
}
