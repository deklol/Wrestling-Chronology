package uk.local.wrestlingchronology;

import static org.junit.Assert.*;

import java.util.*;
import org.json.*;
import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.*;

public class CatalogueTest {
  @Test
  public void progressOnlyShowsCompleteWhenWatched() {
    assertEquals(50, Catalog.progressPercent(30000, 60000, false));
    assertEquals(99, Catalog.progressPercent(60000, 60000, false));
    assertEquals(99, Catalog.progressPercent(90000, 60000, false));
    assertEquals(100, Catalog.progressPercent(0, 0, true));
    assertEquals(0, Catalog.progressPercent(30000, 0, false));
  }

  @Test
  public void clashUsesPpvCategoryAndPreservesItsTitleAndId() throws Exception {
    JSONObject data =
        episode("1997-08-21", "Clash of the Champions").put("title", "Clash of the Champions XXXV");
    Catalog.Episode item = new Catalog.Episode(data);
    assertEquals("PPV", item.show);
    assertEquals("Clash of the Champions XXXV", item.heading());
    assertEquals(data.getString("id"), item.id);
  }

  @Test
  public void oldSuperbrawlIsNotInventedAsANewThunder() throws Exception {
    JSONObject data = data();
    data.getJSONArray("eventCalendar")
        .put(new JSONObject("{name:'WCW SuperBrawl IV',date:'1994-02-20',category:'ppv'}"));
    data.getJSONArray("episodes")
        .put(episode("2000-02-20", "PPV").put("title", "WCW SuperBrawl 2000"));
    String title = "FULL EVENT: WCW SuperBrawl IV – Flair vs. Vader inside Thundercage";
    assertEquals("PPV", CatalogSync.show(title));
    assertEquals("1994-02-20", CatalogSync.date(title, data));
    JSONObject bad = episode("2000-02-20", "Thunder");
    bad.getJSONArray("sources")
        .put(
            new JSONObject()
                .put("type", "youtube")
                .put("title", title)
                .put("videoId", "TWq5iuBnYMw"));
    data.getJSONArray("episodes").put(bad);
    Catalog.normalizeYoutube(data);
    for (int i = 0; i < data.getJSONArray("episodes").length(); i++)
      assertNotEquals("Thunder", data.getJSONArray("episodes").getJSONObject(i).getString("show"));
  }

  @Test
  public void sourceManifestCanDisableCollectionsWithoutPlayerChanges() throws Exception {
    JSONObject data = data(), row = episode("1997-01-06", "Nitro");
    row.getJSONArray("sources")
        .put(new JSONObject("{type:'archive',url:'https://archive.org/download/keep/episode.mp4'}"))
        .put(
            new JSONObject(
                "{type:'archive',url:'https://archive.org/download/remove/episode.mp4'}"));
    data.getJSONArray("episodes").put(row);
    JSONObject config =
        new JSONObject(
            "{schemaVersion:1,youtube:{enabled:false,channelId:'UCbGG2hv4cWmIrBAIhGiCdSg',playlists:[]},archive:{enabled:true,collectionsPerScan:12,collections:[{id:'keep',name:'Keep"
                + " this',enabled:true},{id:'remove',enabled:false}]}}");
    SourceConfig.apply(data, config);
    assertEquals(1, row.getJSONArray("sources").length());
    assertEquals("keep", data.getJSONArray("archiveItems").getString(0));
    assertEquals(0, data.getJSONArray("playlists").length());
  }

  @Test
  public void migrationKeepsTheBundledTrustedCatalogue() throws Exception {
    JSONObject data =
        new JSONObject(
            new String(
                java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("src/main/assets/libraries/wcw.json")),
                java.nio.charset.StandardCharsets.UTF_8));
    int rows = data.getJSONArray("episodes").length(), sources = 0;
    java.util.List<String> mismatches = new java.util.ArrayList<>();
    for (int i = 0; i < rows; i++) {
      JSONObject row = data.getJSONArray("episodes").getJSONObject(i);
      for (int j = 0; j < row.getJSONArray("sources").length(); j++) {
        JSONObject source = row.getJSONArray("sources").getJSONObject(j);
        if (!source.optString("type").equals("youtube")) continue;
        if (!row.getString("date").equals(CatalogSync.date(source.optString("title"), data))
            || !row.getString("show").equals(CatalogSync.show(source.optString("title"))))
          mismatches.add(
              row.getString("id")
                  + ": "
                  + source.optString("title")
                  + " -> "
                  + CatalogSync.date(source.optString("title"), data)
                  + " / "
                  + CatalogSync.show(source.optString("title")));
      }
    }
    assertTrue(String.join("\n", mismatches), mismatches.isEmpty());
    for (int i = 0; i < rows; i++)
      sources += data.getJSONArray("episodes").getJSONObject(i).getJSONArray("sources").length();
    Catalog.normalizeYoutube(data);
    int after = 0;
    for (int i = 0; i < data.getJSONArray("episodes").length(); i++)
      after += data.getJSONArray("episodes").getJSONObject(i).getJSONArray("sources").length();
    assertEquals(rows, data.getJSONArray("episodes").length());
    assertEquals(sources, after);
  }

  @Test
  public void fingerprintIgnoresTrackingButDetectsMetadataChanges() throws Exception {
    JSONObject a =
        new JSONObject(
            "{videoId:'abcdefghijk',title:{simpleText:'FULL EPISODE: Nitro, Jan. 6,"
                + " 1997'},lengthText:{simpleText:'1:45:00'},trackingParams:'random-one'}");
    JSONObject b = new JSONObject(a.toString());
    b.put("trackingParams", "different-on-every-request");
    Set<String> full = new HashSet<>();
    assertEquals(CatalogSync.identity(a, full), CatalogSync.identity(b, full));
    assertEquals(1, full.size());
    b.getJSONObject("title").put("simpleText", "FULL EPISODE: Nitro, Jan. 13, 1997");
    assertNotEquals(CatalogSync.identity(a, full), CatalogSync.identity(b, full));
  }

  JSONObject data() throws Exception {
    return new JSONObject(
        "{episodes:[],eventCalendar:[{name:'WCW Fall Brawl - War Games"
            + " 1998',date:'1998-09-13',category:'ppv'}],archiveItems:[],playlists:[],showArtwork:{}}");
  }

  JSONObject episode(String date, String show) throws Exception {
    return new JSONObject()
        .put("id", date + ":" + show.toLowerCase(Locale.UK))
        .put("date", date)
        .put("year", Integer.parseInt(date.substring(0, 4)))
        .put("show", show)
        .put("title", show)
        .put("sources", new JSONArray());
  }

  AudioStream audio(Locale locale, AudioTrackType type) {
    return new AudioStream.Builder()
        .setId("140")
        .setContent("https://example.invalid/audio", true)
        .setMediaFormat(MediaFormat.M4A)
        .setAudioLocale(locale)
        .setAudioTrackType(type)
        .setAverageBitrate(128)
        .build();
  }

  @Test
  public void originalEnglishBeatsGermanDub() {
    assertTrue(Resolver.audioRank(audio(null, null)) < 1000);
    assertTrue(Resolver.audioRank(audio(Locale.GERMAN, AudioTrackType.ORIGINAL)) >= 1000);
    assertTrue(
        Resolver.audioRank(audio(Locale.US, AudioTrackType.ORIGINAL))
            < Resolver.audioRank(audio(Locale.GERMAN, AudioTrackType.DUBBED)));
    assertTrue(Resolver.audioRank(audio(Locale.GERMAN, AudioTrackType.DUBBED)) >= 1000);
    assertTrue(
        Resolver.audioRank(audio(Locale.US, AudioTrackType.ORIGINAL))
            < Resolver.audioRank(audio(Locale.UK, AudioTrackType.DESCRIPTIVE)));
  }

  @Test
  public void ppvAliasMapsAndInvalidDatesStayOut() throws Exception {
    assertEquals("1998-09-13", CatalogSync.date("FULL EVENT: WCW Fall Brawl 1998", data()));
    assertNull(CatalogSync.validDate(1997, 2, 30));
    assertNull(CatalogSync.validDate(2020, 7, 24));
    assertEquals("1997-01-06", ArchiveSync.fileDate("WCW.Nitro.1997.01.06.mp4", data()));
    assertNull(ArchiveSync.fileDate("Nitro.01.02.1997.mp4", data()));
  }

  @Test
  public void archiveDiscoversFullBroadcastButRejectsMatchAndPart() throws Exception {
    JSONObject data = data(), nitro = episode("1997-01-06", "Nitro");
    data.getJSONArray("episodes").put(nitro);
    nitro
        .getJSONArray("sources")
        .put(
            new JSONObject()
                .put("type", "archive")
                .put("url", "https://archive.org/download/nitro-set/1997-01-06.mp4"));
    JSONObject files =
        new JSONObject(
            "{metadata:{title:'WCW"
                + " Nitro'},files:[{name:'1997-01-13.mp4',length:'6500',size:'600000000',height:'480'},{name:'1997-01-20.full_match.mp4',length:'3000'},{name:'1997-01-27.mp4',length:'300'},{name:'1997-02-03.part1.mp4',length:'4000'}]}");
    assertEquals(1, ArchiveSync.merge(data, "nitro-set", files));
    assertEquals(2, data.getJSONArray("episodes").length());
    assertEquals("1997-01-13", data.getJSONArray("episodes").getJSONObject(1).getString("date"));
    assertEquals(0, ArchiveSync.merge(data, "nitro-set", files));
  }

  @Test
  public void cacheGetsBundledFixesWithoutLosingDiscoveries() throws Exception {
    JSONObject cache = data(),
        seed = data(),
        old = episode("1998-09-13", "PPV"),
        updated = episode("1998-09-13", "PPV");
    old.put("addedAt", 123L).put("title", "Old title");
    old.getJSONArray("sources")
        .put(new JSONObject().put("type", "youtube").put("videoId", "abcdefghijk"));
    cache.getJSONArray("episodes").put(old).put(episode("2000-01-03", "Nitro"));
    updated.put("poster", "https://example.invalid/poster.jpg").put("title", "WCW Fall Brawl 1998");
    updated
        .getJSONArray("sources")
        .put(
            new JSONObject()
                .put("type", "archive")
                .put("url", "https://archive.org/download/test/file.mp4"));
    seed.getJSONArray("episodes").put(updated);
    Catalog.mergeSeed(cache, seed);
    Catalog.mergeSeed(cache, seed);
    assertEquals(2, cache.getJSONArray("episodes").length());
    assertEquals(2, old.getJSONArray("sources").length());
    assertEquals(123L, old.getLong("addedAt"));
    assertEquals("WCW Fall Brawl 1998", old.getString("title"));
    assertTrue(old.has("poster"));
  }

  @Test
  public void latestRowsAreSeparateAndNewestDiscoveredFirst() throws Exception {
    List<Catalog.Episode> saved = Catalog.episodes;
    try {
      ArrayList<Catalog.Episode> rows = new ArrayList<>();
      for (int i = 1; i <= 25; i++)
        rows.add(
            new Catalog.Episode(
                episode(String.format(Locale.UK, "1997-01-%02d", i), "Nitro")
                    .put("discoveredAt", i == 1 ? 100 : i)));
      rows.add(new Catalog.Episode(episode("1997-01-01", "Thunder").put("discoveredAt", 1000)));
      rows.add(new Catalog.Episode(episode("1997-01-01", "PPV").put("discoveredAt", 1000)));
      Catalog.episodes = rows;
      ArrayList<Catalog.Episode> recent = Catalog.recent("Nitro");
      assertEquals(20, recent.size());
      assertEquals("1997-01-01", recent.get(0).date);
      assertEquals("1997-01-25", recent.get(1).date);
      assertEquals("1997-01-07", recent.get(19).date);
      assertEquals(1, Catalog.recent("Thunder").size());
      assertEquals(1, Catalog.recent("PPV").size());
    } finally {
      Catalog.episodes = saved;
    }
  }
}
