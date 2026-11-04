package uk.local.wrestlingchronology;

import android.content.Context;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cronet.CronetDataSource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.chromium.net.CronetEngine;

/** One shared embedded network engine; media stays streamed through the native player. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class StreamingNetwork {
  private static final ExecutorService callbacks = Executors.newFixedThreadPool(2);
  private static CronetEngine engine;
  private static java.util.Set<String> preparedRecordings;

  static DataSource.Factory preparedArchive(Context context, DataSource.Factory ordinary,
      androidx.media3.datasource.HttpDataSource.Factory ranges) {
    boolean enabled = BuildConfig.ARCHIVE_BOUNDED_READS;
    if (BuildConfig.DEBUG && context instanceof android.app.Activity) {
      android.content.Intent intent = ((android.app.Activity) context).getIntent();
      enabled = intent.getBooleanExtra("test_archive_ranges", enabled);
    }
    if (!enabled) return ordinary;
    java.util.Set<String> recordings = preparedRecordings(context);
    return recordings.isEmpty() ? ordinary
        : () -> new PreparedArchiveDataSource(ordinary, ranges, recordings);
  }

  private static synchronized java.util.Set<String> preparedRecordings(Context context) {
    if (preparedRecordings != null) return preparedRecordings;
    java.util.Set<String> result = new java.util.HashSet<>();
    try (java.io.InputStream input = context.getAssets().open("prepared-archive-ranges.json");
         java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) != -1) {
        bytes.write(buffer, 0, count);
        if (bytes.size() > 65536) throw new java.io.IOException("Prepared recording list too large");
      }
      org.json.JSONArray list = new org.json.JSONArray(bytes.toString("UTF-8"));
      for (int i = 0; i < list.length(); i++) result.add(list.getString(i));
    } catch (java.io.IOException | org.json.JSONException error) { result.clear(); }
    preparedRecordings = java.util.Collections.unmodifiableSet(result);
    return preparedRecordings;
  }

  /** Shared by episode and TV playback; other providers keep their own transports. */
  static DataSource.Factory bufferedMedia(Resolver.Resolved stream,
      DataSource.Factory ordinary, DataSource.Factory ranges, Runnable received) {
    if (stream == null || stream.edge) return ordinary;
    boolean archive = "archive".equals(stream.provider);
    boolean dash = "okru".equals(stream.provider) && stream.dash;
    if (!archive && !dash) return ordinary;
    java.util.Set<String> manifests = new java.util.HashSet<>(stream.videos);
    return () -> new BufferedRangeDataSource(ordinary, ranges, spec -> {
      if (dash) return !manifests.contains(spec.uri.toString()) && Okru.mediaUrl(spec.uri.toString());
      String host = spec.uri.getHost();
      return host != null && "https".equals(spec.uri.getScheme())
          && (host.equals("archive.org") || host.endsWith(".archive.org"));
    }, received, archive ? 1024 * 1024 : SegmentWindow.BLOCK);
  }

  static synchronized DataSource.Factory dailymotion(Context context) {
    return factory(context);
  }

  static synchronized DataSource.Factory okru(Context context) {
    java.util.Map<String, String> headers = new java.util.HashMap<>();
    headers.put("User-Agent", Resolver.USER_AGENT);
    headers.put("Referer", "https://ok.ru/");
    return factory(context).setDefaultRequestProperties(headers);
  }

  private static CronetDataSource.Factory factory(Context context) {
    if (engine == null) {
      engine =
          new org.chromium.net.impl.NativeCronetProvider(context.getApplicationContext())
              .createBuilder()
              .enableHttp2(true)
              .enableQuic(true)
              .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISABLED, 0)
              .build();
    }
    return new CronetDataSource.Factory(engine, callbacks)
        .setConnectionTimeoutMs(12000)
        .setReadTimeoutMs(15000);
  }
}
