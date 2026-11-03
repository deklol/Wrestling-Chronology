package uk.local.wrestlingchronology;

import static org.junit.Assert.*;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class CollectionApiTest {
  private JSONObject stream(String provider, String type, String url) throws Exception {
    return new JSONObject().put("provider", provider).put("via", "edge")
        .put("parts", new JSONArray().put(new JSONObject().put("url", url)
            .put("type", type).put("delivery", "edge")));
  }

  @Test public void signedHlsUsesAdaptiveTracksAndNoProviderHostRewrite() throws Exception {
    Resolver.Resolved result = CollectionApi.parse(stream("okru", "hls", "https://delivery.example/media?u=source&e=123&s=sig&m=chunk"), "okru");
    assertTrue(result.edge);
    assertTrue(result.hls);
    assertFalse(result.dash);
    assertTrue(result.qualityUrls.isEmpty());
    assertNull(result.originHost);
    assertNull(result.deliveryHost);
    assertEquals(1, result.videos.size());
  }

  @Test public void archiveRetainsPartOrderForExistingResumeState() throws Exception {
    JSONObject response = stream("archive", "mp4", "https://delivery.example/media?part=first");
    response.getJSONArray("parts").put(new JSONObject().put("url", "https://delivery.example/media?part=second")
        .put("type", "mp4").put("delivery", "edge"));
    Resolver.Resolved result = CollectionApi.parse(response, "archive");
    assertFalse(result.hls);
    assertEquals(2, result.videos.size());
    assertTrue(result.videos.get(0).endsWith("first"));
    assertTrue(result.videos.get(1).endsWith("second"));
  }

  @Test public void metadataDoesNotPermitUnmarkedOrUnrelatedStreams() throws Exception {
    for (String url : new String[]{"http://delivery.example/media?u=x", "https://user@delivery.example/media?u=x",
        "https://delivery.example/download/test", "/media/signed", "https://delivery.example/media?u=x#fragment"}) {
      try { CollectionApi.parse(stream("archive", "mp4", url), "archive"); fail(url); }
      catch (IOException expected) { }
    }
    try { CollectionApi.parse(stream("youtube", "hls", "https://delivery.example/media?u=x"), "youtube"); fail(); }
    catch (IOException expected) { }
    JSONObject response = stream("okru", "mp4", "https://delivery.example/media?u=x");
    try { CollectionApi.parse(response, "okru"); fail(); } catch (IOException expected) { }
  }

  @Test public void onlyMissingDisabledOrNewerCataloguePermitsDeviceDirectFallback() {
    assertTrue(CollectionApi.unavailable(new CommunityApi.ApiError(404, "", "")));
    assertTrue(CollectionApi.unavailable(new CommunityApi.ApiError(503, "edge_not_configured", "")));
    assertTrue(CollectionApi.unavailable(new CommunityApi.ApiError(422, "catalogue_mismatch", "")));
    assertFalse(CollectionApi.unavailable(new CommunityApi.ApiError(422, "", "")));
    assertFalse(CollectionApi.unavailable(new CommunityApi.ApiError(401, "catalogue_mismatch", "")));
    for (int status : new int[]{401, 403, 422, 429, 500, 502, 503, 504})
      assertFalse(CollectionApi.unavailable(new CommunityApi.ApiError(status, "edge_unavailable", "")));
  }

  @Test public void catalogueIdentityOmitsPresentationAndOtherMetadata() throws Exception {
    JSONObject source = new JSONObject().put("type", "okru").put("videoId", "123456789")
        .put("label", "Recording").put("owner", "987654321");
    JSONObject selected = CollectionApi.selection(source);
    assertEquals(2, selected.length());
    assertEquals("123456789", selected.getString("videoId"));
    assertFalse(selected.has("owner"));
  }
}
