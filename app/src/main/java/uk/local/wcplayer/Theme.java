package uk.local.wcplayer;

final class Theme {
  static final Theme INSTANCE = new Theme();
  int primary() { return 0xff304d79; }
  int secondary() { return 0xff242b43; }
  int background() { return 0xff0a0c12; }
  int surface() { return 0xff181e2a; }
  int accent() { return 0xff91baff; }
}
