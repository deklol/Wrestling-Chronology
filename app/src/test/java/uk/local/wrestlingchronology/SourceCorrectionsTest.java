package uk.local.wrestlingchronology;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.*;
import org.junit.Test;

public class SourceCorrectionsTest {
  private JSONObject read(String file) throws Exception {
    return new JSONObject(new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8));
  }

  private JSONObject episode(JSONObject catalogue, String id) throws Exception {
    JSONArray rows = catalogue.getJSONArray("episodes");
    for (int i = 0; i < rows.length(); i++)
      if (id.equals(rows.getJSONObject(i).getString("id"))) return rows.getJSONObject(i);
    throw new AssertionError("Missing " + id);
  }

  @Test public void oldCacheIsRepairedEvenAfterItAlreadyDownloadedTheNewPack() throws Exception {
    JSONObject pack = read("../catalogue/aew.json");
    JSONObject data = pack.getJSONObject("catalogue");
    JSONObject main = episode(data, "aew:2026-10-17:ppv");
    String oldId = "aew:2026-10-17:ppv:saturdaytailgatebrawlwrestledream1017saturdaytailgatebrawl";
    JSONObject tailgate = episode(data, oldId);
    tailgate.getJSONArray("sources").put(main.getJSONArray("sources").getJSONObject(0));
    for (int n = 0; n < 2; n++) SourceCorrections.apply(data, pack.getJSONObject("sources"));
    assertEquals(1, tailgate.getJSONArray("sources").length());
    assertEquals("16404576930415", tailgate.getJSONArray("sources").getJSONObject(0).getString("videoId"));
    assertEquals("16404582173295", main.getJSONArray("sources").getJSONObject(0).getString("videoId"));
    assertEquals(15288, main.getInt("duration"));
    assertEquals("WrestleDream", main.getString("title"));
  }

  @Test public void remoteMergeDoesNotReintroduceTheFullPpvUnderTailgate() throws Exception {
    JSONObject incoming = read("../catalogue/aew.json");
    JSONObject previous = new JSONObject(incoming.toString());
    JSONObject data = previous.getJSONObject("catalogue");
    String oldId = "aew:2026-10-17:ppv:saturdaytailgatebrawlwrestledream1017saturdaytailgatebrawl";
    episode(data, oldId).getJSONArray("sources").put(episode(data, "aew:2026-10-17:ppv")
        .getJSONArray("sources").getJSONObject(0));
    JSONObject result = LibraryPack.merge(previous, incoming).getJSONObject("catalogue");
    assertEquals(1, episode(result, oldId).getJSONArray("sources").length());
    assertEquals(2, episode(data, oldId).getJSONArray("sources").length());
    assertEquals(1, episode(result, "aew:2026-10-17:ppv").getJSONArray("sources").length());
  }

  @Test public void missingDestinationCannotDeleteASource() throws Exception {
    JSONObject data = read("../catalogue/aew.json").getJSONObject("catalogue");
    JSONObject main = episode(data, "aew:2026-10-17:ppv");
    data.put("sourceAssignments", new JSONObject().put("okru:16404582173295", "missing"));
    SourceCorrections.apply(data, null);
    assertEquals(1, main.getJSONArray("sources").length());
  }
}
