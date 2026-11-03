package uk.local.wrestlingchronology;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.*;
import org.junit.Test;

public class TvGuideTest {
  @Test
  public void darkSideGetsItsOwnBoundedStartupWindow() throws Exception {
    JSONObject value = new JSONObject().put("id", "darkside").put("name", "Dark Side TV").put("number", 304);
    assertEquals(20000, new TvGuide.Channel(value).startupTimeoutMs);
    assertEquals(25000, new TvGuide.Channel(value.put("startupTimeoutSeconds", 25)).startupTimeoutMs);
    value.put("id", "wwe").remove("startupTimeoutSeconds");
    assertEquals(0, new TvGuide.Channel(value).startupTimeoutMs);
  }

  @Test
  public void channelBoundariesDoNotOverlapOrSeekPastTheEnd() throws Exception {
    JSONObject data =
        new JSONObject(
            "{\"start\":1000,\"end\":87400,\"oldest\":0,\"expires\":99999,\"channels\":[{\"id\":\"wwe\",\"name\":\"FedTV\",\"number\":101},{\"id\":\"empty\",\"name\":\"Empty\",\"number\":102}],\"programmes\":{\"a\":{\"title\":\"Raw\"},\"b\":{\"title\":\"SmackDown\"}},\"slots\":{\"wwe\":[{\"id\":\"1\",\"programme\":\"a\",\"start\":1000,\"end\":2000},{\"id\":\"2\",\"programme\":\"b\",\"start\":2000,\"end\":3000}]}}");
    TvGuide guide = new TvGuide(data);
    assertEquals(1, guide.channels.size());
    TvGuide.Channel channel = guide.channel("wwe");
    assertNull(channel.at(999));
    assertEquals("Raw", channel.at(1999).title);
    assertEquals("SmackDown", channel.at(2000).title);
    assertNull(channel.at(3000));
    TvGuide.Slot slot = channel.slots.get(0);
    assertEquals(0, slot.offset(500));
    assertEquals(500000, slot.offset(1500));
    assertEquals(999000, slot.offset(4000));
  }

  @Test
  public void newCompanyPacksRetainEveryReviewedEpisodeAndAlternative() throws Exception {
    Company saved = Company.current;
    try {
      for (Company company : new Company[] {Company.NJPW, Company.NXT}) {
        Company.current = company;
        JSONObject pack =
            new JSONObject(
                new String(
                    Files.readAllBytes(Paths.get("../catalogue/" + company.id + ".json")),
                    StandardCharsets.UTF_8));
        LibraryPack.validate(pack, company);
        JSONArray rows = pack.getJSONObject("catalogue").getJSONArray("episodes");
        int count = rows.length(), sources = 0;
        for (int i = 0; i < count; i++)
          sources += rows.getJSONObject(i).getJSONArray("sources").length();
        SourceConfig.apply(pack.getJSONObject("catalogue"), pack.getJSONObject("sources"));
        assertEquals(company.id, count, rows.length());
        int after = 0;
        for (int i = 0; i < rows.length(); i++)
          after += rows.getJSONObject(i).getJSONArray("sources").length();
        assertEquals(company.id, sources, after);
      }
    } finally {
      Company.current = saved;
    }
  }
}
