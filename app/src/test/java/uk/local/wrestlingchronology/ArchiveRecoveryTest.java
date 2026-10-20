package uk.local.wrestlingchronology;

import static org.junit.Assert.*;

import org.junit.Test;

public class ArchiveRecoveryTest {
  @Test
  public void stallsReconnectTwiceThenTryAnotherSource() {
    ArchiveRecovery recovery = new ArchiveRecovery();
    for (int attempt = 0; attempt < 3; attempt++) {
      long now = attempt * 15000L;
      assertEquals(ArchiveRecovery.Action.NONE, recovery.sample(now, true, false, 1, 42000, 42000));
      assertEquals(
          ArchiveRecovery.Action.NONE, recovery.sample(now + 5999, true, false, 1, 42000, 42000));
      assertEquals(
          attempt < 2 ? ArchiveRecovery.Action.RECONNECT : ArchiveRecovery.Action.NEXT_SOURCE,
          recovery.sample(now + 6000, true, false, 1, 42000, 42000));
    }
  }

  @Test
  public void growingBufferGetsTimeToFinish() {
    ArchiveRecovery recovery = new ArchiveRecovery();
    for (int second = 0; second < 60; second++)
      assertEquals(
          ArchiveRecovery.Action.NONE,
          recovery.sample(second * 1000L, true, false, 0, 4000, 4000 + second * 300));
  }

  @Test
  public void pauseOrAudioFocusLossCancelsStallTimer() {
    ArchiveRecovery recovery = new ArchiveRecovery();
    recovery.sample(0, true, false, 0, 4000, 4000);
    assertEquals(ArchiveRecovery.Action.NONE, recovery.sample(12000, false, false, 0, 4000, 4000));
    assertEquals(ArchiveRecovery.Action.NONE, recovery.sample(60000, true, false, 0, 4000, 4000));
    assertEquals(
        ArchiveRecovery.Action.RECONNECT, recovery.sample(66000, true, false, 0, 4000, 4000));
  }

  @Test
  public void seekAndMultipartTransitionStartFreshObservation() {
    ArchiveRecovery recovery = new ArchiveRecovery();
    recovery.sample(0, true, false, 0, 60000, 60000);
    assertEquals(ArchiveRecovery.Action.NONE, recovery.sample(12000, true, false, 0, 30000, 31000));
    assertEquals(ArchiveRecovery.Action.NONE, recovery.sample(17000, true, false, 1, 0, 0));
    assertEquals(ArchiveRecovery.Action.NONE, recovery.sample(22000, true, false, 1, 0, 0));
  }

  @Test
  public void sustainedPlaybackRestoresRetryBudget() {
    ArchiveRecovery recovery = new ArchiveRecovery();
    recovery.sample(0, true, false, 0, 4000, 4000);
    recovery.sample(12000, true, false, 0, 4000, 4000);
    for (int second = 0; second <= 32; second++)
      recovery.sample(
          13000 + second * 1000L, true, true, 0, 4000 + second * 1000L, 20000 + second * 1000L);
    assertEquals(0, recovery.reconnects);
    assertEquals(
        ArchiveRecovery.Action.RECONNECT, recovery.sample(57000, true, false, 0, 36000, 52000));
  }
}
