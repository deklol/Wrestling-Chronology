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

  /** Shared by episode and TV playback; other providers keep their own transports. */
  static DataSource.Factory bufferedMedia(Resolver.Resolved stream,
      DataSource.Factory ordinary, DataSource.Factory ranges, Runnable received) {
    if (stream == null) return ordinary;
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
