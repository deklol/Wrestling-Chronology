package uk.local.wrestlingchronology;

/** Detects stalled playback without mistaking pauses or growing buffers for dead connections. */
final class ArchiveRecovery {
  enum Action {
    NONE,
    RECONNECT,
    NEXT_SOURCE
  }

  static final long STALL_MS = 6000;
  static final long HEALTHY_MS = 30000;
  static final int MAX_RECONNECTS = 2;
  long lastPosition = -1, bufferedHighWater, progressAt, healthySince = -1;
  int lastPart = -1, reconnects;

  void reset() {
    lastPart = -1;
    lastPosition = -1;
    healthySince = -1;
    reconnects = 0;
  }

  Action sample(
      long now, boolean eligible, boolean playing, int part, long position, long buffered) {
    if (!eligible) {
      lastPart = -1;
      lastPosition = -1;
      healthySince = -1;
      return Action.NONE;
    }
    if (lastPosition < 0 || part != lastPart) {
      lastPart = part;
      lastPosition = position;
      bufferedHighWater = buffered;
      progressAt = now;
      healthySince = playing ? now : -1;
      return Action.NONE;
    }
    boolean moved = Math.abs(position - lastPosition) >= 250;
    boolean filling = buffered > bufferedHighWater + 250;
    if (moved || filling) {
      progressAt = now;
      lastPosition = position;
      bufferedHighWater = buffered;
    }
    if (playing && moved) {
      if (healthySince < 0) healthySince = now;
      if (now - healthySince >= HEALTHY_MS) reconnects = 0;
    } else healthySince = -1;
    if (now - progressAt < STALL_MS) return Action.NONE;
    // Start a fresh observation window after each action; never repeatedly seek every tick.
    lastPosition = -1;
    healthySince = -1;
    return reconnects++ < MAX_RECONNECTS ? Action.RECONNECT : Action.NEXT_SOURCE;
  }
}
