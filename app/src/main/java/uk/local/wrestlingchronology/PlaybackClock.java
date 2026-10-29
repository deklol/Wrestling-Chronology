package uk.local.wrestlingchronology;

/** Wall-clock qualification, independent of seek position and playback speed. */
final class PlaybackClock {
  private long accumulated, started = -1;
  void playing(boolean playing, long now) {
    if (started >= 0) accumulated += Math.max(0, now - started);
    started = playing ? now : -1;
  }
  long elapsed(long now) { return accumulated + (started >= 0 ? Math.max(0, now - started) : 0); }
  void reset(long now, boolean playing) { accumulated = 0; started = playing ? now : -1; }
}
