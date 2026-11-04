package uk.local.wrestlingchronology;

import static org.junit.Assert.*;
import org.junit.Test;

public class SeekScrubTest {
  @Test public void holdingAcceleratesButReleaseKeepsTheTargetAndResetsSpeed() {
    SeekScrub seek = new SeekScrub();
    seek.begin(100000, 7200000);
    assertEquals(110000, seek.move(1, 0));
    assertEquals(120000, seek.move(1, 100));
    assertEquals(150000, seek.move(1, 1100));
    assertEquals(210000, seek.move(1, 3100));
    assertEquals(330000, seek.move(1, 6100));
    seek.begin(seek.position, seek.duration);
    assertEquals(340000, seek.move(1, 6200));
  }

  @Test public void reverseResetsSpeedAndRapidRepeatsAreBounded() {
    SeekScrub seek = new SeekScrub();
    seek.begin(600000, 7200000);
    seek.move(1, 0);
    assertEquals(670000, seek.move(1, 3100));
    assertEquals(670000, seek.move(1, 3110));
    assertEquals(660000, seek.move(-1, 3120));
  }

  @Test public void cannotSeekOutsideTheRecording() {
    SeekScrub seek = new SeekScrub();
    seek.begin(0, 60000);
    assertEquals(0, seek.move(-1, 0));
    seek.set(59000);
    assertEquals(59000, seek.move(1, 100));
    seek.set(-100);
    assertEquals(0, seek.position);
  }
}
