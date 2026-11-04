package uk.local.wrestlingchronology;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A continuous prepared recording, fetched one bounded HTTP range at a time. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class PreparedArchiveDataSource extends BaseDataSource {
  static final int BLOCK = 4 * 1024 * 1024;
  private final DataSource.Factory ordinary;
  private final HttpDataSource.Factory ranges;
  private final Set<String> recordings;
  private DataSpec original;
  private DataSource input;
  private Map<String, List<String>> headers = Collections.emptyMap();
  private long position, remaining, total, blockRemaining;
  private boolean bounded, started;
  private volatile boolean closed;
  private static final class ChangedRecording extends IOException {
    ChangedRecording() { super("Prepared recording length changed"); }
  }

  PreparedArchiveDataSource(DataSource.Factory ordinary, HttpDataSource.Factory ranges, Set<String> recordings) {
    super(true);
    this.ordinary = ordinary;
    this.ranges = ranges;
    this.recordings = recordings;
  }

  static boolean eligible(DataSpec spec, Set<String> recordings) {
    Uri uri = spec.uri;
    String host = uri.getHost();
    if (spec.httpMethod != DataSpec.HTTP_METHOD_GET || !"https".equals(uri.getScheme())
        || host == null || !host.endsWith(".workers.dev") || !"/media".equals(uri.getPath())
        || !"prepared".equals(uri.getQueryParameter("m"))
        || uri.getQueryParameter("s") == null || uri.getQueryParameter("e") == null) return false;
    for (String key : spec.httpRequestHeaders.keySet())
      if (key.equalsIgnoreCase("Range") || key.regionMatches(true, 0, "If-", 0, 3)) return false;
    try {
      String encoded = uri.getQueryParameter("u");
      if (encoded == null) return false;
      String decoded = new String(android.util.Base64.decode(encoded,
          android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP), java.nio.charset.StandardCharsets.UTF_8);
      if (!recordings.contains(decoded)) return false;
      Uri source = Uri.parse(decoded);
      String origin = source.getHost();
      return "https".equals(source.getScheme()) && origin != null
          && (origin.equals("archive.org") || origin.endsWith(".archive.org"));
    } catch (IllegalArgumentException error) { return false; }
  }

  @Override public long open(DataSpec spec) throws IOException {
    closed = false;
    original = spec;
    position = spec.position;
    remaining = spec.length;
    total = C.LENGTH_UNSET;
    blockRemaining = 0;
    bounded = eligible(spec, recordings);
    transferInitializing(spec);
    if (bounded) {
      try { openBlock(); }
      catch (IOException error) { fallback(error); }
    } else openOrdinary();
    transferStarted(spec);
    started = true;
    return remaining;
  }

  private void checkOpen() throws InterruptedIOException {
    if (closed || Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("Archive read cancelled");
  }

  private void openBlock() throws IOException {
    checkOpen();
    int count = (int) Math.min(BLOCK, remaining == C.LENGTH_UNSET ? BLOCK : remaining);
    if (count <= 0) return;
    Map<String, String> requestHeaders = new HashMap<>(original.httpRequestHeaders);
    requestHeaders.put("Range", "bytes=" + position + "-" + (position + count - 1));
    // Validate the returned offset ourselves before any bytes reach the extractor.
    // A server returning 200 must not make the HTTP source skip gigabytes first.
    DataSpec part = original.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET)
        .setFlags(original.flags & ~DataSpec.FLAG_ALLOW_GZIP).setHttpRequestHeaders(requestHeaders).build();
    HttpDataSource next = ranges.createDataSource();
    input = next;
    next.open(part);
    checkOpen();
    headers = next.getResponseHeaders();
    if (next.getResponseCode() != 206 || header(headers, "X-Prepared-Index") == null)
      throw new IOException("Prepared byte ranges unavailable");
    SegmentRange range = new SegmentRange(header(headers, "Content-Range"), position, count);
    if (total != C.LENGTH_UNSET && range.total != total)
      throw new ChangedRecording();
    total = range.total;
    if (remaining == C.LENGTH_UNSET) remaining = total - position;
    else remaining = Math.min(remaining, total - position);
    blockRemaining = range.length();
  }

  private void openOrdinary() throws IOException {
    checkOpen();
    DataSpec rest = original.subrange(position - original.position);
    input = ordinary.createDataSource();
    long available = input.open(rest);
    checkOpen();
    headers = input.getResponseHeaders();
    if (remaining == C.LENGTH_UNSET) remaining = available;
    else if (available != C.LENGTH_UNSET) remaining = Math.min(remaining, available);
  }

  private void fallback(IOException error) throws IOException {
    checkOpen();
    closeInput();
    if (error instanceof ChangedRecording) throw error;
    bounded = false;
    if (BuildConfig.DEBUG) android.util.Log.i("ArchiveRanges", "FALLBACK offset=" + position
        + " reason=" + error.getClass().getSimpleName());
    // Reopen only the next undelivered virtual byte. The player stays on the same recording.
    openOrdinary();
  }

  @Override public int read(byte[] buffer, int offset, int length) throws IOException {
    if (length == 0) return 0;
    checkOpen();
    if (remaining == 0) return C.RESULT_END_OF_INPUT;
    if (bounded && blockRemaining == 0) {
      closeInput();
      try { openBlock(); }
      catch (IOException error) { fallback(error); }
    }
    int count = remaining == C.LENGTH_UNSET ? length : (int) Math.min(length, remaining);
    if (bounded) count = (int) Math.min(count, blockRemaining);
    int read;
    try {
      read = input.read(buffer, offset, count);
      if (bounded && read == C.RESULT_END_OF_INPUT)
        throw new EOFException("Prepared range ended early");
    } catch (IOException error) {
      if (!bounded) throw error;
      fallback(error);
      return read(buffer, offset, length);
    }
    if (read > 0) {
      position += read;
      if (remaining != C.LENGTH_UNSET) remaining -= read;
      if (bounded) blockRemaining -= read;
      bytesTransferred(read);
    }
    return read;
  }

  static String header(Map<String, List<String>> headers, String name) {
    for (Map.Entry<String, List<String>> entry : headers.entrySet())
      if (name.equalsIgnoreCase(entry.getKey()) && !entry.getValue().isEmpty())
        return entry.getValue().get(0);
    return null;
  }

  private void closeInput() throws IOException {
    DataSource current = input;
    input = null;
    if (current != null) current.close();
  }

  @Override public Uri getUri() { return original == null ? null : original.uri; }
  @Override public Map<String, List<String>> getResponseHeaders() { return headers; }
  @Override public void close() throws IOException {
    closed = true;
    try { closeInput(); }
    finally {
      original = null;
      if (started) { started = false; transferEnded(); }
    }
  }
}
