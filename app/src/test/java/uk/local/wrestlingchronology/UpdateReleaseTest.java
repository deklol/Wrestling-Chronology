package uk.local.wrestlingchronology;

import static org.junit.Assert.*;

import java.io.IOException;
import org.json.*;
import org.junit.Test;

public class UpdateReleaseTest {
  static final String HASH = "a".repeat(64);

  static UpdateRelease release(int code, String version) throws Exception {
    return new UpdateRelease(code, version, "https://example.com/app.apk", HASH, "Release notes");
  }

  @Test
  public void skippedVersionIsAvailableThroughManualUpdate() throws Exception {
    UpdateRelease release = release(8, "0.2.6");
    assertFalse(release.shouldOffer(7, "0.2.5", "v0.2.6", true));
    assertTrue(release.shouldOffer(7, "0.2.5", "0.2.6", false));
    assertTrue(release.shouldOffer(7, "0.2.5", "0.2.4", true));
  }

  @Test
  public void newerReleaseIsNotSuppressedByOlderSkip() throws Exception {
    assertTrue(release(9, "0.2.7").shouldOffer(7, "0.2.5", "0.2.6", true));
  }

  @Test
  public void sameOrOlderVersionNeverUpdatesJustBecauseHashChanged() throws Exception {
    assertFalse(release(7, "0.2.5").shouldOffer(7, "0.2.5", "", false));
    assertFalse(release(6, "0.2.4").shouldOffer(7, "0.2.5", "", false));
  }

  @Test
  public void githubVersionsCompareNumerically() throws Exception {
    assertTrue(release(-1, "v0.2.10").newerThan(7, "0.2.9"));
    assertFalse(release(-1, "0.2.9").newerThan(7, "0.2.10"));
    assertFalse(release(-1, "0.2.5.0").newerThan(7, "0.2.5"));
  }

  @Test
  public void manifestRequiresVersionAndVerifiedHttpsAsset() throws Exception {
    JSONObject manifest =
        new JSONObject()
            .put("versionCode", 8)
            .put("versionName", "0.2.6")
            .put("apkUrl", "https://example.com/app.apk")
            .put("sha256", HASH);
    assertEquals(8, UpdateRelease.manifest(manifest).code);
    for (String url :
        new String[] {
          "http://example.com/app.apk",
          "file:///tmp/update.apk",
          "https://user:pass@example.com/app.apk"
        }) {
      try {
        UpdateRelease.manifest(new JSONObject(manifest.toString()).put("apkUrl", url));
        fail(url);
      } catch (IOException expected) {
      }
    }
    try {
      UpdateRelease.manifest(manifest.put("sha256", "bad"));
      fail();
    } catch (IOException expected) {
    }
  }

  @Test
  public void githubSelectsStableApkAndItsDigest() throws Exception {
    JSONObject github =
        new JSONObject()
            .put("tag_name", "v0.2.6")
            .put(
                "assets",
                new JSONArray()
                    .put(new JSONObject().put("name", "source.zip"))
                    .put(
                        new JSONObject()
                            .put("name", "Wrestling-Chronology.apk")
                            .put("digest", "sha256:" + HASH)
                            .put("browser_download_url", "https://example.com/app.apk")));
    assertEquals("0.2.6", UpdateRelease.github(github).version);
    try {
      UpdateRelease.github(github.put("prerelease", true));
      fail();
    } catch (IOException expected) {
    }
    github.put("prerelease", false).getJSONArray("assets").getJSONObject(1).remove("digest");
    try {
      UpdateRelease.github(github);
      fail();
    } catch (IOException expected) {
    }
  }
}
