package uk.local.wrestlingchronology;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.*;
import org.junit.*;

public class LibraryPackTest {
  @Test
  public void downloadedPackMustMatchHashAndByteCountBeforeParsing() throws Exception {
    JSONObject pack = seed(Company.WWE);
    byte[] bytes = pack.toString().getBytes(StandardCharsets.UTF_8);
    String hash = LibraryPack.sha256(bytes);
    assertEquals(
        "wwe", LibraryPack.verified(bytes, bytes.length, hash, Company.WWE).getString("company"));
    byte[] changed = bytes.clone();
    changed[changed.length / 2] ^= 1;
    assertThrows(
        java.io.IOException.class,
        () -> LibraryPack.verified(changed, bytes.length, hash, Company.WWE));
    assertThrows(
        java.io.IOException.class,
        () -> LibraryPack.verified(bytes, bytes.length + 1, hash, Company.WWE));
  }

  @After
  public void restore() {
    Company.current = Company.WCW;
  }

  private JSONObject seed(Company company) throws Exception {
    Company.current = company;
    return new JSONObject()
        .put("schemaVersion", 1)
        .put("company", company.id)
        .put("revision", 1)
        .put("catalogue", read("src/main/assets/libraries/" + company.id + ".json"))
        .put("sources", read("../sources/" + company.id + ".json"));
  }

  private JSONObject read(String path) throws Exception {
    return new JSONObject(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8));
  }

  @Test
  public void everyBundledLibraryHasValidSourcesAndDates() throws Exception {
    for (Company company : Company.ALL) {
      JSONObject pack = seed(company);
      LibraryPack.validate(pack, company);
      SourceConfig.apply(pack.getJSONObject("catalogue"), pack.getJSONObject("sources"));
      Catalog.replace(pack.getJSONObject("catalogue"));
      assertFalse(company.name, Catalog.episodes.isEmpty());
      for (Catalog.Episode episode : Catalog.episodes)
        assertTrue(episode.id, !episode.sources.isEmpty());
    }
  }

  @Test
  public void missingRowsInUpdateNeverRemoveExistingShows() throws Exception {
    JSONObject previous = seed(Company.WWE);
    int count = previous.getJSONObject("catalogue").getJSONArray("episodes").length();
    JSONObject incoming = new JSONObject(previous.toString());
    incoming
        .getJSONObject("catalogue")
        .put(
            "episodes",
            new JSONArray()
                .put(
                    incoming.getJSONObject("catalogue").getJSONArray("episodes").getJSONObject(0)));
    JSONObject merged = LibraryPack.merge(previous, incoming);
    assertEquals(count, merged.getJSONObject("catalogue").getJSONArray("episodes").length());
  }

  @Test
  public void correctedMetadataKeepsDiscoveryTimeAndSourceAlternatives() throws Exception {
    JSONObject previous = seed(Company.WWE);
    JSONObject first =
        previous.getJSONObject("catalogue").getJSONArray("episodes").getJSONObject(0);
    first.put("discoveredAt", 123L);
    JSONObject incoming = new JSONObject(previous.toString());
    JSONObject revised =
        incoming.getJSONObject("catalogue").getJSONArray("episodes").getJSONObject(0);
    revised.put("title", "Corrected broadcast title").put("discoveredAt", 999L);
    revised
        .getJSONArray("sources")
        .put(
            new JSONObject(first.getJSONArray("sources").getJSONObject(0).toString())
                .put("url", "https://archive.org/download/WAR-1995/alternate.mp4"));
    JSONObject merged = LibraryPack.merge(previous, incoming);
    JSONObject result = merged.getJSONObject("catalogue").getJSONArray("episodes").getJSONObject(0);
    assertEquals("Corrected broadcast title", result.getString("title"));
    assertEquals(123L, result.getLong("discoveredAt"));
    assertTrue(result.getJSONArray("sources").length() > first.getJSONArray("sources").length());
  }

  @Test
  public void onlyExplicitSourceRetirementRemovesARecording() throws Exception {
    JSONObject previous = seed(Company.ECW);
    JSONArray rows = previous.getJSONObject("catalogue").getJSONArray("episodes");
    String retired =
        Catalog.sourceKey(rows.getJSONObject(0).getJSONArray("sources").getJSONObject(0));
    JSONObject incoming =
        new JSONObject(previous.toString()).put("retiredSources", new JSONArray().put(retired));
    JSONObject result = LibraryPack.merge(previous, incoming);
    assertEquals(
        rows.length() - 1, result.getJSONObject("catalogue").getJSONArray("episodes").length());
  }

  @Test
  public void sourceConfigOmissionDoesNotPruneKnownCollections() throws Exception {
    JSONObject previous = seed(Company.WWE);
    JSONObject incoming = new JSONObject(previous.toString());
    incoming.getJSONObject("sources").getJSONObject("archive").put("collections", new JSONArray());
    JSONObject merged = LibraryPack.merge(previous, incoming);
    LibraryPack.validate(merged, Company.WWE);
    assertEquals(
        previous
            .getJSONObject("sources")
            .getJSONObject("archive")
            .getJSONArray("collections")
            .length(),
        merged
            .getJSONObject("sources")
            .getJSONObject("archive")
            .getJSONArray("collections")
            .length());
  }

  @Test
  public void anotherCompanyCannotReplaceThisLibrary() throws Exception {
    JSONObject pack = seed(Company.ECW);
    assertThrows(Exception.class, () -> LibraryPack.validate(pack, Company.WWE));
  }

  @Test
  public void externalArchiveLookalikeIsRejected() throws Exception {
    JSONObject pack = seed(Company.WWE);
    pack.getJSONObject("catalogue")
        .getJSONArray("episodes")
        .getJSONObject(0)
        .getJSONArray("sources")
        .getJSONObject(0)
        .put("url", "https://archive.org.example.com/download/WAR-1995/episode.mp4");
    assertThrows(Exception.class, () -> LibraryPack.validate(pack, Company.WWE));
  }

  @Test
  public void duplicateEpisodeIdsAreRejected() throws Exception {
    JSONObject pack = seed(Company.ECW);
    JSONArray rows = pack.getJSONObject("catalogue").getJSONArray("episodes");
    rows.put(rows.getJSONObject(0));
    assertThrows(Exception.class, () -> LibraryPack.validate(pack, Company.ECW));
  }

  @Test
  public void zeroEpisodeUpdateIsRejected() throws Exception {
    JSONObject pack = seed(Company.TNA);
    pack.getJSONObject("catalogue").put("episodes", new JSONArray());
    assertThrows(Exception.class, () -> LibraryPack.validate(pack, Company.TNA));
  }

  @Test
  public void metadataHashesAreStableAndDetectChangedBytes() throws Exception {
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        LibraryPack.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    assertNotEquals(
        LibraryPack.sha256("abc".getBytes(StandardCharsets.UTF_8)),
        LibraryPack.sha256("abd".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void credentialsAndCleartextAreNotValidFeeds() {
    assertFalse(LibraryPack.https("http://example.com/manifest.json"));
    assertFalse(LibraryPack.https("https://username:password@example.com/manifest.json"));
    assertTrue(
        LibraryPack.https(
            "https://raw.githubusercontent.com/example/app/main/catalogue/manifest.json"));
  }
}
