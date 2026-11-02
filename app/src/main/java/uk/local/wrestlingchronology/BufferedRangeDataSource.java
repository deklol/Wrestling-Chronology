package uk.local.wrestlingchronology;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded read-ahead for range-capable media. Manifests retain their normal transport. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class BufferedRangeDataSource extends BaseDataSource {
  private static final ExecutorService WORKERS = Executors.newFixedThreadPool(4, task -> {
    Thread thread = new Thread(task, "media-range");
    thread.setDaemon(true);
    return thread;
  });
  private final DataSource.Factory factory, ranges;
  private final java.util.function.Predicate<DataSpec> eligible;
  private final Runnable received;
  private final int block;
  private DataSource plain;
  private SegmentWindow window;
  private Uri mediaUri;
  private Map<String, List<String>> headers = Collections.emptyMap();
  private boolean started;
  private java.util.concurrent.atomic.AtomicBoolean cancelled;

  BufferedRangeDataSource(DataSource.Factory factory, DataSource.Factory ranges,
      java.util.function.Predicate<DataSpec> eligible, Runnable received, int block) {
    super(true);
    this.factory = factory;
    this.ranges = ranges;
    this.eligible = eligible;
    this.received = received;
    this.block = block;
  }

  @Override public long open(DataSpec spec) throws IOException {
    final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
    cancelled = stopped;
    transferInitializing(spec);
    mediaUri = spec.uri;
    boolean segment = spec.httpMethod == DataSpec.HTTP_METHOD_GET && eligible.test(spec);
    if (segment) {
      int length = (int) Math.min(SegmentWindow.BLOCK, spec.length == C.LENGTH_UNSET ? SegmentWindow.BLOCK : spec.length);
      DataSource first = ranges.createDataSource();
      try {
        first.open(rangeSpec(spec, spec.position, length));
        headers = first.getResponseHeaders();
        String contentRange = header(headers, "Content-Range");
        if (contentRange != null) {
          SegmentRange range = new SegmentRange(contentRange, spec.position, length);
          long end = spec.length == C.LENGTH_UNSET ? range.total
              : spec.position + Math.min(spec.length, range.total - spec.position);
          // Archive redirects to its storage server. Reuse that exact URL for
          // subsequent pieces instead of repeating the download-page redirect.
          Uri redirected = first.getUri();
          if (redirected != null) mediaUri = redirected;
          transferStarted(spec);
          started = true;
          byte[] bytes = readExactly(first, range.length(), stopped);
          final DataSpec redirectedSpec = spec.withUri(mediaUri);
          window = new SegmentWindow(bytes, spec.position, end,
              (at, count) -> fetch(redirectedSpec, at, count, range.total, stopped), WORKERS, block);
          return end - spec.position;
        }
      } finally { first.close(); }
    }
    // A host that does not support ranges retains its original playback path.
    plain = factory.createDataSource();
    long length = plain.open(spec);
    headers = plain.getResponseHeaders();
    transferStarted(spec);
    started = true;
    return length;
  }

  private byte[] fetch(DataSpec spec, long start, int count, long total,
      java.util.concurrent.atomic.AtomicBoolean stopped) throws IOException {
    IOException failed = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      if (stopped.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Video read cancelled");
      try { return fetchRange(spec, start, count, total, stopped); }
      catch (IOException error) {
        if (stopped.get() || Thread.currentThread().isInterrupted()) throw error;
        if (BuildConfig.DEBUG) android.util.Log.i("WCWPlayer", "RANGE_RETRY host="
            + spec.uri.getHost() + " error=" + error.getClass().getSimpleName()
            + (error instanceof HttpDataSource.InvalidResponseCodeException
                ? " status=" + ((HttpDataSource.InvalidResponseCodeException) error).responseCode : ""));
        failed = error;
      }
    }
    throw failed;
  }

  private byte[] fetchRange(DataSpec spec, long start, int count, long total,
      java.util.concurrent.atomic.AtomicBoolean stopped) throws IOException {
    DataSource source = ranges.createDataSource();
    try {
      if (stopped.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Video read cancelled");
      source.open(rangeSpec(spec, start, count));
      SegmentRange range = new SegmentRange(header(source.getResponseHeaders(), "Content-Range"), start, count);
      if (range.total != total) throw new IOException("Video segment changed during playback");
      return readExactly(source, range.length(), stopped);
    } finally { source.close(); }
  }

  private static DataSpec rangeSpec(DataSpec original, long start, int count) {
    Map<String, String> headers = new HashMap<>(original.httpRequestHeaders);
    headers.keySet().removeIf(key -> "Range".equalsIgnoreCase(key));
    headers.put("Range", "bytes=" + start + "-" + (start + count - 1));
    // OK.ru sometimes returns 200 for a valid range. A nonzero DataSpec.position
    // would make Cronet skip those bytes again, so validate Content-Range ourselves.
    return original.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET)
        .setFlags(original.flags & ~DataSpec.FLAG_ALLOW_GZIP).setHttpRequestHeaders(headers).build();
  }

  private byte[] readExactly(DataSource source, int count,
      java.util.concurrent.atomic.AtomicBoolean stopped) throws IOException {
    byte[] result = new byte[count];
    int used = 0;
    while (used < count) {
      if (stopped.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Video read cancelled");
      int read = source.read(result, used, Math.min(16384, count - used));
      if (read == C.RESULT_END_OF_INPUT) throw new EOFException("Incomplete video segment");
      used += read;
      if (read > 0) received.run();
    }
    return result;
  }

  private static String header(Map<String, List<String>> headers, String name) {
    for (Map.Entry<String, List<String>> entry : headers.entrySet())
      if (name.equalsIgnoreCase(entry.getKey()) && !entry.getValue().isEmpty()) return entry.getValue().get(0);
    return null;
  }

  @Override public int read(byte[] buffer, int offset, int count) throws IOException {
    int read = window == null ? plain.read(buffer, offset, count) : window.read(buffer, offset, count);
    if (read > 0) { received.run(); bytesTransferred(read); }
    return read;
  }

  @Override public Uri getUri() { return plain != null ? plain.getUri() : mediaUri; }
  @Override public Map<String, List<String>> getResponseHeaders() { return headers; }
  @Override public void close() throws IOException {
    if (cancelled != null) cancelled.set(true);
    try {
      if (window != null) window.close();
      // Each worker owns and closes its socket. Closing an Android HTTP stream
      // from another thread can race its read and corrupt the platform timeout.
      // Cancellation is checked between bounded reads; socket timeouts cap stalls.
      if (plain != null) plain.close();
    } finally {
      window = null;
      plain = null;
      cancelled = null;
      if (started) { started = false; transferEnded(); }
    }
  }
}
