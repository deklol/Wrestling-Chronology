package uk.local.wrestlingchronology;

import java.io.*;
import java.util.ArrayDeque;
import java.util.concurrent.*;

/** Ordered, bounded reads: a paused consumer never occupies a network worker. */
final class SegmentWindow implements Closeable {
  static final int BLOCK = 256 * 1024;
  static final int AHEAD = 4;
  interface Fetch { byte[] read(long start, int count) throws IOException; }

  private final Fetch fetch;
  private final ExecutorService workers;
  private final long end;
  private final int block;
  private final ArrayDeque<Future<byte[]>> pending = new ArrayDeque<>();
  private byte[] current;
  private int offset;
  private long next;
  private boolean closed;

  SegmentWindow(byte[] first, long start, long end, Fetch fetch, ExecutorService workers) {
    this(first, start, end, fetch, workers, BLOCK);
  }

  SegmentWindow(byte[] first, long start, long end, Fetch fetch, ExecutorService workers, int block) {
    if (first.length == 0 || start < 0 || start + first.length > end)
      throw new IllegalArgumentException("Invalid segment window");
    this.current = first;
    this.next = start + first.length;
    this.end = end;
    this.fetch = fetch;
    this.workers = workers;
    this.block = block;
    fill();
  }

  private void fill() {
    while (!closed && next < end && pending.size() < AHEAD) {
      long start = next;
      int count = (int) Math.min(block, end - start);
      pending.add(workers.submit(() -> {
        byte[] bytes = fetch.read(start, count);
        if (bytes.length != count) throw new EOFException("Incomplete video range");
        return bytes;
      }));
      next += count;
    }
  }

  int read(byte[] target, int from, int count) throws IOException {
    if (closed) throw new IOException("Segment reader closed");
    if (count == 0) return 0;
    if (current == null || offset == current.length) {
      current = null;
      if (pending.isEmpty()) return -1;
      Future<byte[]> nextBlock = pending.removeFirst();
      try {
        current = nextBlock.get();
      } catch (InterruptedException error) {
        nextBlock.cancel(true);
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("Video read interrupted");
      } catch (ExecutionException error) {
        Throwable cause = error.getCause();
        throw cause instanceof IOException ? (IOException) cause : new IOException("Video range failed", cause);
      } catch (CancellationException error) {
        throw new InterruptedIOException("Video read cancelled");
      }
      offset = 0;
      fill();
    }
    int copied = Math.min(count, current.length - offset);
    System.arraycopy(current, offset, target, from, copied);
    offset += copied;
    return copied;
  }

  @Override public void close() {
    closed = true;
    for (Future<?> future : pending) future.cancel(true);
    pending.clear();
    current = null;
    if (workers instanceof ThreadPoolExecutor) ((ThreadPoolExecutor) workers).purge();
  }
}
