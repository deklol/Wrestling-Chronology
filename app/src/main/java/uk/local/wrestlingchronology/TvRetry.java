package uk.local.wrestlingchronology;

/** A transient outage gets two fresh connections; repeated failures cannot loop forever. */
final class TvRetry {
  private int failures;
  private long playingSince = -1;

  void reset() { failures = 0; playingSince = -1; }
  void playing(long now) { playingSince = now; }

  long nextDelay(long now) {
    if (playingSince >= 0 && now - playingSince >= 30000) failures = 0;
    playingSince = -1;
    if (failures >= 2) return -1;
    return failures++ == 0 ? 500 : 1500;
  }

  static boolean retryHttp(int status) {
    return status == 401 || status == 403 || status == 408 || status == 410
        || status == 429 || status >= 500;
  }
}
