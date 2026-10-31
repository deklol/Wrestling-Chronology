package uk.local.wrestlingchronology;

import static org.junit.Assert.*;
import org.junit.Test;

public class BufferedQualitySelectionTest {
  @Test public void fastDeliveryRetainsAPlayableCushion() {
    assertEquals(3000000L, BufferedQualitySelection.reserveUs(6000000L, 2000000, 20000000L));
  }

  @Test public void slowDeliveryRetainsEnoughTimeToFetchTheSelectedQuality() {
    assertEquals(19000000L, BufferedQualitySelection.reserveUs(6000000L, 4000000, 2000000L));
  }

  @Test public void missingMeasurementsDoNotDiscardTheWholeBuffer() {
    assertEquals(6000000L, BufferedQualitySelection.reserveUs(6000000L, -1, 0));
  }
}
