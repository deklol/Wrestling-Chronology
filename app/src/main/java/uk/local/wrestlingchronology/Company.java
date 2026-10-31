package uk.local.wrestlingchronology;

import android.content.Context;
import android.graphics.Color;
import android.widget.ImageView;
import java.io.File;
import java.util.Arrays;
import java.util.List;

/** A company owns its library, source configuration and viewing history. */
final class Company {
  static final Company WWE =
      new Company(
          "wwe",
          "WWF / WWE",
          "1985–2026",
          R.drawable.logo_wwe,
          "1985-01-01",
          "2028-12-31",
          new String[] {"Raw", "SmackDown", "Heat"});
  static final Company WCW =
      new Company(
          "wcw",
          "WCW",
          "1983–2001",
          R.drawable.logo_wcw,
          "1983-01-01",
          "2001-03-26",
          new String[] {"Nitro", "Thunder"});
  static final Company ECW =
      new Company(
          "ecw",
          "ECW",
          "1992–2006",
          R.drawable.logo_ecw,
          "1992-01-01",
          "2006-12-31",
          new String[] {"Hardcore TV", "ECW on TNN"});
  static final Company TNA =
      new Company(
          "tna",
          "TNA / Impact",
          "2002–2026",
          R.drawable.logo_tna,
          "2002-01-01",
          "2028-12-31",
          new String[] {"Impact", "Weekly PPV"});
  static final Company AEW = new Company("aew", "AEW", "2018–2026", R.drawable.logo_aew,
      "2018-01-01", "2028-12-31", new String[] {"Dynamite", "Collision", "Rampage", "Dark", "Dark: Elevation"});
  static final Company ROH = new Company("roh", "Ring of Honor", "2002–2026", R.drawable.logo_roh,
      "2002-02-23", "2028-12-31", new String[] {"HonorClub", "ROH TV", "HDNet", "High Impact TV"});
  static final List<Company> ALL = Arrays.asList(WWE, WCW, ECW, TNA, AEW, ROH);
  // WCW is the default for the inherited parser tests; entry points select explicitly.
  static volatile Company current = WCW;
  final String id, name, period, firstDate, lastDate;
  final String[] shows;
  final int logo;

  private Company(
      String id,
      String name,
      String period,
      int logo,
      String firstDate,
      String lastDate,
      String[] shows) {
    this.id = id;
    this.name = name;
    this.period = period;
    this.logo = logo;
    this.firstDate = firstDate;
    this.lastDate = lastDate;
    this.shows = shows;
  }

  static Company find(String id) {
    for (Company company : ALL) if (company.id.equals(id)) return company;
    return WWE;
  }

  static void restore(Context context) {
    current = find(context.getSharedPreferences("application", 0).getString("company", "wwe"));
  }

  static void select(Context context, Company company) {
    synchronized (Catalog.class) {
      if (current == company && !Catalog.episodes.isEmpty()) return;
      current = company;
      context
          .getSharedPreferences("application", 0)
          .edit()
          .putString("company", company.id)
          .apply();
      Catalog.episodes = java.util.Collections.emptyList();
      Catalog.raw = null;
      Catalog.loadedPack = null;
    }
  }

  static File cache(Context context) {
    return new File(context.getFilesDir(), "library-" + current.id + ".json");
  }

  String displayPeriod(Context context) {
    int fallback = Integer.parseInt(period.substring(period.length() - 4));
    int last = context.getSharedPreferences("application", 0).getInt("availableYear:" + id, fallback);
    return period.substring(0, period.length() - 4) + last;
  }

  int hero() {
    if (this == ROH) return R.drawable.hero_roh;
    if (this == AEW) return R.drawable.logo_aew;
    if (this == WWE) return R.drawable.hero_wwe;
    if (this == ECW) return R.drawable.hero_ecw;
    if (this == TNA) return R.drawable.hero_tna;
    return R.drawable.wcw_opening;
  }

  int accent() {
    if (this == ROH) return 0xffff6470;
    if (this == AEW) return 0xffd7b65d;
    if (this == WCW) return 0xff67aeef;
    if (this == TNA) return 0xffedbd64;
    if (this == ECW) return 0xffe36c94;
    return 0xfff17385;
  }

  int primary() {
    if (this == ROH) return 0xff950c20;
    if (this == AEW) return 0xff82662a;
    if (this == WCW) return 0xff225b90;
    if (this == TNA) return 0xff9d3030;
    if (this == ECW) return 0xff98284a;
    return 0xffa12d45;
  }

  int secondary() {
    if (this == ROH) return 0xff27070c;
    if (this == AEW) return 0xff343434;
    if (this == WCW) return 0xff142c45;
    if (this == TNA) return 0xff71501e;
    if (this == ECW) return 0xff532b69;
    return 0xff244b80;
  }

  int background() {
    if (this == ROH) return 0xff090607;
    if (this == AEW) return 0xff090909;
    if (this == WCW) return 0xff080d14;
    if (this == TNA) return 0xff120b09;
    if (this == ECW) return 0xff100912;
    return 0xff0c0c16;
  }

  int surface() {
    if (this == ROH) return 0xff231014;
    if (this == AEW) return 0xff202020;
    if (this == WCW) return 0xff142437;
    if (this == TNA) return 0xff2b1c15;
    if (this == ECW) return 0xff28162d;
    return 0xff1c2034;
  }

  void setLogo(ImageView view) {
    view.setImageResource(logo);
    if (this == WCW || this == ECW) view.setColorFilter(Color.WHITE);
    else view.clearColorFilter();
  }
}
