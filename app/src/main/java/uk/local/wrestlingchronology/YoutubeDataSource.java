package uk.local.wrestlingchronology;

import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.datasource.*;
import java.io.*;
import java.util.*;

/** Bounded streaming reads, with the POST request used by YouTube players. No disk cache. */
@androidx.media3.common.util.UnstableApi
final class YoutubeDataSource implements DataSource {
  private static final long CHUNK = 1024 * 1024;
  private final DataSource upstream;
  private DataSpec original;
  private long position, end, chunkEnd, started, transferred;
  private int request;
  private boolean opened;

  YoutubeDataSource(DataSource upstream) {
    this.upstream = upstream;
  }

  @Override
  public void addTransferListener(TransferListener listener) {
    upstream.addTransferListener(listener);
  }

  @Override
  public long open(DataSpec spec) throws IOException {
    original = spec;
    position = spec.position;
    end = spec.length == C.LENGTH_UNSET ? Long.MAX_VALUE : position + spec.length;
    String length = spec.uri.getQueryParameter("clen");
    if (length != null)
      try {
        end = Math.min(end, Long.parseLong(length));
      } catch (NumberFormatException ignored) {
      }
    if (position >= end) return 0;
    nextChunk();
    return end == Long.MAX_VALUE ? C.LENGTH_UNSET : end - position;
  }

  private void nextChunk() throws IOException {
    long count = Math.min(CHUNK, end - position);
    chunkEnd = position + count;
    Uri uri =
        original.uri.buildUpon().appendQueryParameter("rn", Integer.toString(request++)).build();
    DataSpec spec =
        original
            .buildUpon()
            .setUri(uri)
            .setPosition(position)
            .setLength(count)
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(new byte[] {0x78, 0})
            .build();
    started = SystemClock.elapsedRealtime();
    transferred = 0;
    opened = true;
    upstream.open(spec);
    if (end == Long.MAX_VALUE) {
      for (Map.Entry<String, List<String>> header : upstream.getResponseHeaders().entrySet())
        if ("Content-Range".equalsIgnoreCase(header.getKey())) {
          String value = header.getValue().isEmpty() ? "" : header.getValue().get(0);
          int slash = value.lastIndexOf('/');
          if (slash >= 0)
            try {
              end = Long.parseLong(value.substring(slash + 1));
              chunkEnd = Math.min(chunkEnd, end);
            } catch (NumberFormatException ignored) {
            }
        }
    }
  }

  private void closeChunk() throws IOException {
    if (!opened) return;
    opened = false;
    long elapsed = Math.max(1, SystemClock.elapsedRealtime() - started);
    if (transferred >= 256 * 1024)
      Log.d(
          "WCWPlayer",
          "YOUTUBE_TRANSFER bytes="
              + transferred
              + " ms="
              + elapsed
              + " kbps="
              + (transferred * 8 / elapsed));
    upstream.close();
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (length == 0) return 0;
    if (position >= end) return C.RESULT_END_OF_INPUT;
    if (position >= chunkEnd) {
      closeChunk();
      nextChunk();
    }
    int count = upstream.read(buffer, offset, (int) Math.min(length, chunkEnd - position));
    if (count == C.RESULT_END_OF_INPUT) {
      if (end == Long.MAX_VALUE) {
        end = position;
        return C.RESULT_END_OF_INPUT;
      }
      if (position < Math.min(end, chunkEnd))
        throw new EOFException("YouTube stream ended before the requested range");
      return C.RESULT_END_OF_INPUT;
    }
    position += count;
    transferred += count;
    return count;
  }

  @Override
  public Uri getUri() {
    return original == null ? null : original.uri;
  }

  @Override
  public Map<String, List<String>> getResponseHeaders() {
    return upstream.getResponseHeaders();
  }

  @Override
  public void close() throws IOException {
    try {
      closeChunk();
    } finally {
      original = null;
    }
  }
}
