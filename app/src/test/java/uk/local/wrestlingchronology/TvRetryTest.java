package uk.local.wrestlingchronology;

import static org.junit.Assert.*;
import org.junit.Test;

public class TvRetryTest {
  @Test public void aBriefSuccessfulConnectionDoesNotCreateAnEndlessRetryLoop() {
    TvRetry retry = new TvRetry();
    assertEquals(500, retry.nextDelay(1000));
    retry.playing(5000);
    assertEquals(1500, retry.nextDelay(6000));
    retry.playing(7000);
    assertEquals(-1, retry.nextDelay(8000));
    assertEquals(-1, retry.nextDelay(100000));
  }

  @Test public void aLaterOutageAfterStablePlaybackGetsAFreshBudget() {
    TvRetry retry = new TvRetry();
    retry.nextDelay(1000); retry.nextDelay(2000);
    retry.playing(4000);
    assertEquals(500, retry.nextDelay(34000));
  }

  @Test public void aDifferentChannelStartsFresh() {
    TvRetry retry = new TvRetry();
    retry.nextDelay(0); retry.nextDelay(1);
    retry.reset();
    assertEquals(500, retry.nextDelay(2));
  }

  @Test public void refreshExpiredLinksAndServerFailuresButNotMissingFiles() {
    for (int status : new int[]{401, 403, 408, 410, 429, 500, 502, 503, 504}) assertTrue(TvRetry.retryHttp(status));
    for (int status : new int[]{400, 404, 405, 416}) assertFalse(TvRetry.retryHttp(status));
  }
}
