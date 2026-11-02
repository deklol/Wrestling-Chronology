package uk.local.wrestlingchronology;

/** MP4 sample tables can cost more heap than the buffered picture and sound. */
final class TvMemory {
  private static final long MIB = 1024 * 1024;

  static boolean needsSinglePlayer(long heapLimit, long used, long headerBytes,
      double durationSeconds, boolean progressive) {
    // Parsing keeps the raw index alongside per-sample arrays. Allow room for
    // both, incoming media, and the rest of the UI before starting a second player.
    long index = progressive ? Math.max(headerBytes * 4,
        (long) (Math.max(0, durationSeconds) * 4096)) : 0;
    long needed = 24 * MIB + Math.max(16 * MIB, index);
    return heapLimit - used < needed;
  }

  static boolean exhausted(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause())
      if (cause instanceof OutOfMemoryError) return true;
    return false;
  }
}
