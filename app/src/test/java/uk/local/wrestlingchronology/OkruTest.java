package uk.local.wrestlingchronology;

import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;

public class OkruTest {
  @Test public void qualityProbeRespectsCeilingAndRejectsForeignHosts() throws Exception {
    JSONObject metadata = new JSONObject().put("videos", new JSONArray()
        .put(new JSONObject().put("name", "full").put("url", "https://a.okcdn.ru/full"))
        .put(new JSONObject().put("name", "hd").put("url", "https://a.vkuser.net/hd"))
        .put(new JSONObject().put("name", "sd").put("url", "https://a.mycdn.me/sd"))
        .put(new JSONObject().put("name", "hd").put("url", "https://evil.example/file")));
    assertEquals("https://a.vkuser.net/hd", Okru.progressive(metadata, 720));
    assertEquals("https://a.mycdn.me/sd", Okru.progressive(metadata, 480));
    assertNull(Okru.progressive(metadata, 360));
    assertFalse(Okru.mediaUrl("https://okcdn.ru.evil.example/file"));
    assertFalse(Okru.mediaUrl("https://127.0.0.1/file"));
  }
}
