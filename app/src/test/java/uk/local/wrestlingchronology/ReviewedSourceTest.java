package uk.local.wrestlingchronology;

import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;

public class ReviewedSourceTest {
  @Test public void reviewedWeeklyStillRequiresTheExactUploaderAndTitle() throws Exception {
    Company saved = Company.current;
    try {
      Company.current = Company.WWE;
      JSONObject entry = new JSONObject().put("id", "abcdefghijk")
          .put("channelId", "UCfm0ffHhindlFDQxxZSc04w")
          .put("authorUrl", "https://www.youtube.com/@example")
          .put("date", "1993-05-01").put("title", "Superstars").put("show", "Superstars")
          .put("name", "WWF Superstars 05/01/1993").put("duration", 2700)
          .put("reviewedFullShow", true);
      JSONObject metadata = new JSONObject().put("title", entry.getString("name"))
          .put("author_url", entry.getString("authorUrl"));
      JSONObject data = new JSONObject().put("episodes", new JSONArray());
      assertEquals(1, DirectYoutube.merge(data, entry, metadata));
      assertEquals(0, DirectYoutube.merge(data, entry, metadata));
      metadata.put("author_url", "https://www.youtube.com/@someoneelse");
      assertThrows(java.io.IOException.class, () -> DirectYoutube.merge(data, entry, metadata));
      metadata.put("author_url", entry.getString("authorUrl")).put("title", "A single match");
      assertThrows(java.io.IOException.class, () -> DirectYoutube.merge(data, entry, metadata));
    } finally { Company.current = saved; }
  }

  @Test public void foreignLanguageFilterKeepsEnglishAndEventLocations() throws Exception {
    Company saved = Company.current;
    try {
      Company.current = Company.WWE;
      JSONObject s = new JSONObject().put("type", "youtube").put("title", "WWE Raw");
      assertTrue(SourcePolicy.eligible(s));
      assertFalse(SourcePolicy.eligible(s.put("language", "es")));
      assertFalse(SourcePolicy.eligible(s.put("language", "ar")));
      assertFalse(SourcePolicy.eligible(s.put("language", "en").put("title", "WWE Arabic")));
      assertTrue(SourcePolicy.eligible(s.put("title", "Crown Jewel in Saudi Arabia")));
    } finally { Company.current = saved; }
  }
}
