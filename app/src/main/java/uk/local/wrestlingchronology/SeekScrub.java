package uk.local.wrestlingchronology;

/** Builds one seek target while a key is held; releasing commits the position once. */
final class SeekScrub {
  long position, duration;
  private long started, lastStep;
  private int direction;

  void begin(long position, long duration) {
    this.duration = Math.max(0, duration);
    this.position = clamp(position);
    direction = 0;
  }

  long move(int nextDirection, long now) {
    if (direction != nextDirection) {
      direction = nextDirection;
      started = now;
      lastStep = now - 100;
    }
    if (now - lastStep < 100) return position;
    long held = now - started;
    long step = held < 1000 ? 10000 : held < 3000 ? 30000 : held < 6000 ? 60000 : 120000;
    lastStep = now;
    position = clamp(position + direction * step);
    return position;
  }

  void set(long target) { position = clamp(target); }

  private long clamp(long target) {
    return Math.max(0, Math.min(Math.max(0, duration - 1000), target));
  }
}
