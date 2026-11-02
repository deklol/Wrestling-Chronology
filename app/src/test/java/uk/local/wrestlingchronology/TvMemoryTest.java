package uk.local.wrestlingchronology;

import static org.junit.Assert.*;
import org.junit.Test;

public class TvMemoryTest {
  private static final long MIB = 1024 * 1024;

  @Test public void longPpvCannotBeParsedBesideAnExistingPlayerOnSmallHeap() {
    assertTrue(TvMemory.needsSinglePlayer(128*MIB, 64*MIB, 17898704, 16336, true));
    assertTrue(TvMemory.needsSinglePlayer(128*MIB, 64*MIB, 0, 16336, true));
  }

  @Test public void normalStreamsKeepTheOutgoingPictureWhenThereIsRoom() {
    assertFalse(TvMemory.needsSinglePlayer(128*MIB, 64*MIB, 0, 16336, false));
    assertFalse(TvMemory.needsSinglePlayer(256*MIB, 64*MIB, 17898704, 16336, true));
  }

  @Test public void wrappedMemoryFailureIsNotANetworkOutage() {
    assertTrue(TvMemory.exhausted(new java.io.IOException(new OutOfMemoryError())));
    assertFalse(TvMemory.exhausted(new java.net.SocketTimeoutException()));
  }
}
