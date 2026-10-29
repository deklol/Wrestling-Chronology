package uk.local.wrestlingchronology;
import org.junit.Test;
import static org.junit.Assert.*;
public class PlaybackClockTest {
  @Test public void pauseAndBufferingDoNotQualify() {
    PlaybackClock clock = new PlaybackClock(); clock.playing(true, 1000);
    clock.playing(false, 6000); assertEquals(5000, clock.elapsed(80000));
    clock.playing(true, 80000); assertEquals(15000, clock.elapsed(90000));
  }
  @Test public void repeatedStateEventsDoNotDoubleCount() {
    PlaybackClock clock = new PlaybackClock(); clock.playing(true, 1000);
    clock.playing(true, 3000); clock.playing(true, 5000);
    assertEquals(15000, clock.elapsed(16000));
  }
  @Test public void newEpisodeResetsQualification() {
    PlaybackClock clock = new PlaybackClock(); clock.playing(true, 1000);
    clock.reset(9000, true); assertEquals(1000, clock.elapsed(10000));
  }
  @Test public void installationIdentitiesAreIndependentHex() {
    String one = CommunityApi.newIdentity(), two = CommunityApi.newIdentity();
    assertTrue(one.matches("[0-9a-f]{64}")); assertNotEquals(one, two);
  }
}
