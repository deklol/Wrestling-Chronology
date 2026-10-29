package uk.local.wrestlingchronology;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
  final ExecutorService work = Executors.newSingleThreadExecutor();
  final Updater updater = new Updater(this);
  LinearLayout root;
  int year = 1997;
  String page = "home", filter = "All", query = "";
  boolean ready;
  long companyOpenedAt;
  final Handler uiHandler = new Handler(Looper.getMainLooper());
  LinearLayout refreshStrip;
  ProgressBar refreshBar;
  TextView refreshText;
  boolean refreshWasBusy;
  final Runnable libraryTick =
      new Runnable() {
        public void run() {
          if (ready) {
            updater.offer();
            updateRefresh();
            if (CatalogSync.busy) refreshWasBusy = true;
            else if (refreshWasBusy) {
              refreshWasBusy = false;
              render();
              notifyFound(false);
            }
            checkRefresh();
          }
          uiHandler.postDelayed(this, 1000);
        }
      };

  int d(float value) {
    return Ui.dp(this, value);
  }

  TextView text(String value, float size, int color) {
    return Ui.text(this, value, size, color);
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    immersive();
    Company.restore(this);
    if (state != null && !state.getString("page", "companies").equals("companies")) {
      year = state.getInt("year", 1997);
      page = state.getString("page", "home");
      filter = state.getString("filter", "All");
      query = state.getString("query", "");
      openCompany(Company.current, false);
    } else {
      page = "companies";
      FrameLayout intro = new FrameLayout(this);
      intro.setBackgroundColor(Color.BLACK);
      setContentView(intro);
      splash();

    }
  }

  void openCompany(Company company, boolean reset) {
    if (CatalogSync.busy) {
      Toast.makeText(this, "Finishing source update. Please try again shortly.", Toast.LENGTH_SHORT)
          .show();
      return;
    }
    ready = false;
    companyOpenedAt = SystemClock.elapsedRealtime();
    if (reset) {
      page = "home";
      filter = "All";
      query = "";
    }
    TextView loading = text("Opening " + company.name + "...", 22, Ui.WHITE);
    loading.setGravity(Gravity.CENTER);
    loading.setBackgroundColor(Ui.BG);
    setContentView(loading);
    work.execute(
        () -> {
          try {
            Company.select(this, company);
            Catalog.load(this);
            runOnUiThread(
                () -> {
                  if (isFinishing()
                      || isDestroyed()
                      || Company.current != company
                      || page.equals("companies")) return;
                  ready = true;
                  render();
                  handlePlayIntent();
                  LibraryJob.schedule(this);
                  checkRefresh();
                  updater.startup();
                });
          } catch (Exception error) {
            runOnUiThread(
                () ->
                    new AlertDialog.Builder(this)
                        .setMessage("Could not open the library: " + error.getMessage())
                        .setPositiveButton("Companies", (dialog, which) -> companies())
                        .show());
          }
        });
  }

  void companies() {
    if (CatalogSync.busy) {
      Toast.makeText(this, "Finishing source update. Please try again shortly.", Toast.LENGTH_SHORT)
          .show();
      return;
    }
    ready = false;
    page = "companies";
    FrameLayout screen = new FrameLayout(this);
    screen.setBackgroundColor(Ui.BG);
    ImageView background = new ImageView(this);
    background.setImageResource(R.drawable.chronology_opening);
    background.setScaleType(ImageView.ScaleType.CENTER_CROP);
    background.setAlpha(.32f);
    screen.addView(background, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout content = Ui.vertical(this);
    content.setPadding(d(40), d(16), d(40), d(16));
    content.setGravity(Gravity.CENTER);
    FrameLayout.LayoutParams contentBounds = new FrameLayout.LayoutParams(-1, -1);
    contentBounds.bottomMargin = d(64);
    screen.addView(content, contentBounds);
    TextView title = text("WRESTLING CHRONOLOGY", 28, Ui.WHITE);
    title.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
    title.setGravity(Gravity.CENTER);
    content.addView(title);
    Ui.gap(content, 6);
    TextView subtitle = text("Choose your company. Pick a year. Press play.", 14, Ui.MUTED);
    subtitle.setGravity(Gravity.CENTER);
    content.addView(subtitle);
    Ui.gap(content, 14);
    LinearLayout grid = Ui.vertical(this);
    grid.setGravity(Gravity.CENTER);
    content.addView(grid, Ui.lp(this, -1, 296));
    LinearLayout cards = null;
    int cardIndex = 0;
    int cardWidth = (int) ((getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density - 80) / 3) - 10;
    View first = null;
    for (Company company : Company.ALL) {
      if (cardIndex == 0 || cardIndex == 3) {
        cards = Ui.horizontal(this);
        cards.setGravity(Gravity.CENTER);
        grid.addView(cards, Ui.lp(this, -1, 148));
      }
      cardIndex++;
      LinearLayout card = Ui.vertical(this);
      card.setGravity(Gravity.CENTER);
      card.setPadding(d(12), d(10), d(12), d(10));
      card.setBackground(Ui.focus(this, false, company));
      card.setFocusable(true);
      card.setClickable(true);
      card.setContentDescription(company.name + " " + company.displayPeriod(this));
      card.setOnClickListener(view -> openCompany(company, true));
      ImageView logo = new ImageView(this);
      company.setLogo(logo);
      if (company == Company.ECW) {
        logo.setScaleX(2.05f);
        logo.setScaleY(2.05f);
      }
      logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
      card.addView(logo, Ui.lp(this, -1, 56));
      Ui.gap(card, 8);
      TextView name = text(company.name, 17, Ui.WHITE);
      name.setTypeface(null, Typeface.BOLD);
      card.addView(name);
      Ui.gap(card, 4);
      card.addView(text(company.displayPeriod(this), 12, Ui.MUTED));
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(d(cardWidth), -1);
      params.setMargins(d(5), d(4), d(5), d(4));
      cards.addView(card, params);
      if (first == null) first = card;
    }
    Ui.gap(content, 12);
    TextView credit =
        text(
            "Videos are hosted externally on YouTube, Internet Archive or Dailymotion.\n"
                + "The app creator does not upload or host these videos.",
            11,
            Ui.MUTED);
    credit.setGravity(Gravity.CENTER);
    credit.setLineSpacing(d(3), 1);
    content.addView(credit, Ui.lp(this, -1, 42));
    SupportTicker ticker = new SupportTicker(this);
    screen.addView(ticker, new FrameLayout.LayoutParams(-1, d(64), Gravity.BOTTOM));
    setContentView(screen);
    if (first != null) first.requestFocus();
  }

  void immersive() {
    getWindow().getDecorView().setSystemUiVisibility(5894);
  }

  @Override
  protected void onResume() {
    super.onResume();
    immersive();
    updater.onResume();
    if (ready) {
      render();
      notifyFound(false);
    }
    uiHandler.removeCallbacks(libraryTick);
    uiHandler.post(libraryTick);
  }

  @Override
  protected void onPause() {
    updater.onPause();
    uiHandler.removeCallbacks(libraryTick);
    super.onPause();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (BuildConfig.DEBUG && intent.hasExtra("verify_company")) {
      Company company = Company.find(intent.getStringExtra("verify_company"));
      intent.removeExtra("verify_company");
      openCompany(company, true);
    } else if (ready) handlePlayIntent();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putInt("year", year);
    state.putString("page", page);
    state.putString("filter", filter);
    state.putString("query", query);
  }

  void handlePlayIntent() {
    if (BuildConfig.DEBUG && getIntent().getBooleanExtra("verify_memory_cache", false)) {
      getIntent().removeExtra("verify_memory_cache");
      work.execute(() -> {
        android.util.AtomicFile file = new android.util.AtomicFile(Company.cache(this));
        java.io.FileOutputStream output = null;
        try {
          output = file.startWrite();
          JsonStreams.write(output, Catalog.loadedPack);
          file.finishWrite(output);
          android.util.Log.i("Chronology", "CACHE_STREAM_VERIFIED " + Company.current.id
              + " episodes=" + Catalog.episodes.size());
        } catch (Exception error) {
          if (output != null) file.failWrite(output);
          android.util.Log.e("Chronology", "CACHE_STREAM_FAILED", error);
        }
      });
      return;
    }
    if (BuildConfig.DEBUG && getIntent().getBooleanExtra("verify_source_failure", false)) {
      getIntent().removeExtra("verify_source_failure");
      CatalogSync.verifyFailNextPlaylist = true;
      refresh(false);
    }
    String id = getIntent().getStringExtra("play_id");
    if (id != null) {
      getIntent().removeExtra("play_id");
      Catalog.Episode item = Catalog.find(id);
      if (item != null)
        launch(
            item,
            Catalog.forYear(item.year, "All", extras()),
            getIntent().getStringExtra("play_provider"));
    }
  }

  void splash() {
    FrameLayout host = findViewById(android.R.id.content);
    FrameLayout overlay = new FrameLayout(this);
    overlay.setBackgroundColor(Color.BLACK);
    overlay.setClickable(true);
    overlay.setFocusable(true);
    ImageView image = new ImageView(this);
    image.setImageResource(R.drawable.chronology_opening);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.setAlpha(0f);
    overlay.addView(image, new FrameLayout.LayoutParams(-1, -1));
    host.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    Handler handler = new Handler(Looper.getMainLooper());
    long start = SystemClock.uptimeMillis();
    handler.post(
        new Runnable() {
          boolean selectorShown;

          public void run() {
            if (isFinishing() || isDestroyed()) {
              host.removeView(overlay);
              image.setImageDrawable(null);
              return;
            }
            long elapsed = SystemClock.uptimeMillis() - start;
            if (elapsed < 400) image.setAlpha(elapsed / 400f);
            else if (elapsed < 3900) image.setAlpha(1f);
            else if (elapsed < 4450) {
              if (!selectorShown) {
                companies();
                host.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
                selectorShown = true;
              }
              overlay.setAlpha(1 - (elapsed - 3900) / 550f);
            } else {
              host.removeView(overlay);
              image.setImageDrawable(null);
              return;
            }
            handler.postDelayed(this, 16);
          }
        });
  }

  void render() {
    if (page.equals("companies")) {
      companies();
      return;
    }
    root = Ui.vertical(this);
    root.setPadding(d(30), d(18), d(30), d(16));
    root.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[] {Company.current.surface(), Company.current.background(), Ui.BG}));
    setContentView(root);
    LinearLayout nav = Ui.horizontal(this);
    root.addView(nav, Ui.lp(this, -1, 48));
    ImageView logo = new ImageView(this);
    Company.current.setLogo(logo);
    logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
    nav.addView(logo, Ui.lp(this, 62, 46));
    Ui.gap(nav, 12);
    TextView brand = text("", 1, Ui.WHITE);
    brand.setLetterSpacing(.16f);
    brand.setTypeface(null, Typeface.BOLD);
    nav.addView(brand);
    nav.addView(Ui.button(this, "Companies", false, this::companies));
    Ui.weight(nav);
    nav.addView(
        Ui.button(
            this,
            "Home",
            page.equals("home"),
            () -> {
              page = "home";
              render();
            }));
    Ui.gap(nav, 10);
    nav.addView(
        Ui.button(this, "Years", page.equals("years") || page.equals("year"), this::openYears));
    Ui.gap(nav, 10);
    for (String show : Company.current.shows) {
      nav.addView(
          Ui.button(this, show, page.equals("show") && filter.equals(show), () -> openShow(show)));
      Ui.gap(nav, 10);
    }
    nav.addView(
        Ui.button(
            this,
            "PPV",
            page.equals("ppv"),
            () -> {
              page = "ppv";
              year = 0;
              render();
            }));
    Ui.gap(nav, 10);
    nav.addView(Ui.button(this, "Search", false, this::search));
    Ui.gap(nav, 10);
    nav.addView(Ui.button(this, "Settings", false, this::settings));
    for (int i = 0; i < nav.getChildCount(); i++) {
      View child = nav.getChildAt(i);
      if (child.isClickable()) {
        child.setPadding(d(9), d(12), d(9), d(12));
        if (child instanceof TextView) ((TextView) child).setTextSize(12);
      }
    }
    refreshStrip = Ui.vertical(this);
    refreshStrip.setPadding(d(6), d(7), d(6), d(4));
    refreshText = text("", 11, Ui.MUTED);
    refreshStrip.addView(refreshText);
    refreshBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    refreshBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.accent()));
    refreshStrip.addView(refreshBar, Ui.lp(this, -1, 6));
    root.addView(refreshStrip);
    updateRefresh();
    Ui.gap(root, 14);
    if (page.equals("home")) home();
    else if (page.equals("years")) years();
    else library();
  }

  boolean extras() {
    return Company.current != Company.WCW || Catalog.prefs(this).getBoolean("extras", false);
  }

  void home() {
    ScrollView pageScroll = new ScrollView(this);
    pageScroll.setClipToPadding(false);
    pageScroll.setFillViewport(true);
    root.addView(pageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout body = Ui.vertical(this);
    pageScroll.addView(body);
    Catalog.Episode current = Catalog.continueWatching(this);
    FrameLayout hero = new FrameLayout(this);
    body.addView(hero, Ui.lp(this, -1, current == null ? 260 : 300));
    ImageView backdrop = new ImageView(this);
    backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
    backdrop.setImageResource(Company.current.hero());
    if (current != null) {
      String artwork = Art.episodeUrl(current, false);
      Art.loadHero(backdrop, artwork);
    }
    FrameLayout.LayoutParams photo = new FrameLayout.LayoutParams(d(580), -1, Gravity.RIGHT);
    hero.addView(backdrop, photo);
    View shade = new View(this);
    shade.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] {
              Company.current.background(),
              (Company.current.background() & 0x00ffffff) | 0xe0000000,
              (Company.current.background() & 0x00ffffff) | 0x18000000
            }));
    hero.addView(shade, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout copy = Ui.vertical(this);
    copy.setGravity(Gravity.CENTER_VERTICAL);
    copy.setPadding(d(10), d(12), 0, d(16));
    hero.addView(copy, new FrameLayout.LayoutParams(d(510), -1));
    TextView kicker =
        text(
            current == null
                ? "THE " + Company.current.name.toUpperCase(Locale.UK) + " ARCHIVE"
                : "CONTINUE WATCHING",
            11,
            Ui.accent());
    kicker.setLetterSpacing(.2f);
    kicker.setTypeface(null, Typeface.BOLD);
    copy.addView(kicker);
    Ui.gap(copy, 10);
    TextView title =
        text(
            current == null ? Company.current.name + ",\nin order." : current.heading(),
            current == null ? 44 : 34,
            Ui.WHITE);
    title.setMaxLines(2);
    title.setEllipsize(TextUtils.TruncateAt.END);
    title.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
    title.setLineSpacing(0, .96f);
    copy.addView(title);
    Ui.gap(copy, 12);
    if (current != null) {
      TextView meta =
          text(
              Catalog.dateLabel(current.date)
                  + (Catalog.displayDuration(this, current) > 0
                      ? "  ·  " + Catalog.displayDuration(this, current) / 60000 + " min"
                      : "")
                  + (current.show.equals("PPV") ? "  ·  PPV" : ""),
              13,
              Ui.WHITE);
      copy.addView(meta);
      Ui.gap(copy, 9);
    }
    TextView description =
        text(
            current == null
                ? "Full shows and pay-per-views from "
                    + Company.current.name
                    + ".\n"
                    + "One timeline. One player. Pick a year and let it roll."
                : current.description.isEmpty() ? current.title : current.description,
            14,
            Ui.MUTED);
    description.setLineSpacing(d(4), 1);
    description.setMaxLines(2);
    description.setEllipsize(TextUtils.TruncateAt.END);
    copy.addView(description);
    if (current != null) {
      long position = Catalog.displayPosition(this, current),
          duration = Catalog.displayDuration(this, current);
      Ui.gap(copy, 12);
      if (duration > 0) {
        ProgressBar progress =
            new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.accent()));
        progress.setProgressBackgroundTintList(
            android.content.res.ColorStateList.valueOf(0xff3a3d47));
        progress.setMax(100);
        progress.setProgress(Catalog.progressPercent(position, duration, false));
        copy.addView(progress, Ui.lp(this, 350, 4));
        Ui.gap(copy, 7);
      }
      copy.addView(
          text(
              playbackTime(position)
                  + (duration > 0
                      ? " / "
                          + playbackTime(duration)
                          + "  ·  "
                          + Catalog.progressPercent(position, duration, false)
                          + "% watched"
                      : " watched"),
              12,
              Ui.MUTED));
    }
    Ui.gap(copy, 18);
    LinearLayout actions = Ui.horizontal(this);
    copy.addView(actions);
    if (current != null) {
      Catalog.Episode resume = current;
      actions.addView(
          Ui.button(
              this,
              "▶  Continue watching",
              true,
              () ->
                  launch(
                      resume,
                      Catalog.forYear(resume.year, "All", extras() || !resume.main()),
                      null)));
    } else actions.addView(Ui.button(this, "Explore years  ›", true, this::openYears));
    Ui.gap(actions, 10);
    actions.addView(Ui.button(this, "Refresh library", false, () -> refresh(false)));
    CommunityPanel.highlights(this, body);
    for (String show : Company.current.shows)
      recentRail(body, "LATEST FOUND " + show.toUpperCase(Locale.UK), show);
    recentRail(body, "LATEST FOUND PPVs", "PPV");
    Ui.gap(body, 12);
    TextView label = text("WATCH BY YEAR · ALL AVAILABLE SHOWS", 12, Ui.MUTED);
    label.setLetterSpacing(.1f);
    body.addView(label);
    Ui.gap(body, 10);
    body.addView(Ui.button(this, "Browse all years  ›", false, this::openYears));
    Ui.gap(body, 10);
    body.addView(text("STREAMING ONLY   ·   YouTube + Archive.org + Dailymotion", 10, Ui.MUTED));
    Ui.gap(body, 22);
    TextView credit = text("x.com/digitalm1nd", 12, Ui.MUTED);
    credit.setGravity(Gravity.CENTER);
    body.addView(credit, Ui.lp(this, -1, 32));
  }

  void openYears() {
    page = "years";
    render();
  }

  void years() {
    TextView heading = text("Choose a year", 32, Ui.WHITE);
    heading.setTypeface(null, Typeface.BOLD);
    root.addView(heading);
    Ui.gap(root, 8);
    root.addView(
        text("Every available show and event, together in broadcast order.", 14, Ui.MUTED));
    Ui.gap(root, 5);
    root.addView(text(String.format(Locale.UK, "%,d shows and events available across %,d years",
        Catalog.episodes.size(), Catalog.years().size()), 14, Ui.accent()));
    Ui.gap(root, 18);
    ScrollView scroll = new ScrollView(this);
    scroll.setClipToPadding(false);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout body = Ui.vertical(this);
    scroll.addView(body);
    LinearLayout row = null;
    int index = 0;
    for (int value : Catalog.years().descendingSet()) {
      ArrayList<Catalog.Episode> items = Catalog.forYear(value, "All", extras());
      if (items.isEmpty()) continue;
      if (index % 4 == 0) {
        row = Ui.horizontal(this);
        body.addView(row);
      }
      LinearLayout card = Ui.vertical(this);
      card.setPadding(d(20), d(18), d(20), d(18));
      card.setGravity(Gravity.CENTER_VERTICAL);
      card.setBackground(Ui.focus(this, false));
      card.setFocusable(true);
      card.setClickable(true);
      card.setOnClickListener(view -> openYear(value));
      TextView number = text(Integer.toString(value), 36, Ui.WHITE);
      number.setTypeface(null, Typeface.BOLD);
      card.addView(number);
      Ui.gap(card, 10);
      card.addView(
          text(
              items.size() + (items.size() == 1 ? " show available" : " shows available"),
              12,
              Ui.MUTED));
      LinkedHashSet<String> shows = new LinkedHashSet<>();
      for (Catalog.Episode item : items) shows.add(Catalog.showLabel(item.show, value));
      TextView formats = text(String.join(" · ", shows), 10, Ui.MUTED);
      formats.setMaxLines(2);
      formats.setEllipsize(TextUtils.TruncateAt.END);
      Ui.gap(card, 7);
      card.addView(formats);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, d(158), 1);
      params.setMargins(d(2), d(2), d(10), d(10));
      row.addView(card, params);
      index++;
    }
    while (index % 4 != 0) {
      LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(0, d(158), 1);
      spacer.setMargins(d(2), d(2), d(10), d(10));
      row.addView(new View(this), spacer);
      index++;
    }
  }

  static String playbackTime(long millis) {
    long seconds = Math.max(0, millis) / 1000;
    return seconds >= 3600
        ? String.format(Locale.UK, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        : String.format(Locale.UK, "%d:%02d", seconds / 60, seconds % 60);
  }

  void recentRail(LinearLayout body, String heading, String show) {
    ArrayList<Catalog.Episode> values = Catalog.recent(show);
    if (values.isEmpty()) return;
    Ui.gap(body, 20);
    LinearLayout row = Ui.horizontal(this);
    body.addView(row, Ui.lp(this, -1, 42));
    TextView label = text(heading, 14, Ui.WHITE);
    label.setTypeface(null, Typeface.BOLD);
    row.addView(label);
    Ui.gap(row, 12);
    row.addView(text("Newest discoveries first", 11, Ui.MUTED));
    Ui.weight(row);
    if (show.equals("PPV"))
      row.addView(
          Ui.button(
              this,
              "All PPVs  ›",
              false,
              () -> {
                page = "ppv";
                year = 0;
                render();
              }));
    RecyclerView rail = new RecyclerView(this);
    rail.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
    rail.setAdapter(new EpisodeAdapter(values, true, show.equals("PPV")));
    rail.setNestedScrollingEnabled(false);
    rail.setClipToPadding(false);
    body.addView(rail, Ui.lp(this, -1, show.equals("PPV") ? 302 : 208));
  }

  void openYear(int value) {
    year = value;
    page = "year";
    filter = "All";
    render();
  }

  void openShow(String show) {
    filter = show;
    year = 0;
    page = "show";
    render();
  }

  ArrayList<Catalog.Episode> selected() {
    if (page.equals("ppv") || page.equals("show")) {
      ArrayList<Catalog.Episode> result = new ArrayList<>();
      for (Catalog.Episode item : Catalog.episodes)
        if (item.show.equals(page.equals("ppv") ? "PPV" : filter)
            && (year == 0 || item.year == year)) result.add(item);
      return result;
    }
    if (!page.equals("search")) return Catalog.forYear(year, filter, extras());
    ArrayList<Catalog.Episode> result = new ArrayList<>();
    String needle = query.toLowerCase(Locale.UK);
    for (Catalog.Episode item : Catalog.episodes)
      if (!Catalog.hiddenShows.contains(item.show)
          && (item.year < 1997 || extras() || item.main())
          && (item.title + " " + item.show + " " + item.date)
              .toLowerCase(Locale.UK)
              .contains(needle)) result.add(item);
    return result;
  }

  void library() {
    ArrayList<Catalog.Episode> items = selected();
    if (page.equals("year") && filter.equals("All")) root.addView(Ui.button(this, "Filter shows", false, this::showVisibility));
    LinearLayout heading = Ui.horizontal(this);
    root.addView(heading, Ui.lp(this, -1, 56));
    TextView title =
        text(
            page.equals("search")
                ? "Search: " + query
                : page.equals("ppv")
                    ? "Pay-per-views"
                    : page.equals("show") ? filter : Company.current.name + " " + year,
            29,
            Ui.WHITE);
    title.setTypeface(null, Typeface.BOLD);
    heading.addView(title);
    Ui.gap(heading, 18);
    heading.addView(text(items.size() + " full shows · in broadcast order", 12, Ui.MUTED));
    Ui.weight(heading);
    if (!items.isEmpty()) {
      heading.addView(
          Ui.button(this, "▶  Play all", true, () -> launch(items.get(0), items, null)));
      String last = Catalog.prefs(this).getString("last:" + year, "");
      Catalog.Episode resume = Catalog.find(last);
      if (resume != null && items.contains(resume) && Catalog.position(this, resume) > 0) {
        Ui.gap(heading, 10);
        heading.addView(Ui.button(this, "Resume", false, () -> launch(resume, items, null)));
      }
    }
    if (page.equals("year")) {
      HorizontalScrollView filterScroll = new HorizontalScrollView(this);
      filterScroll.setHorizontalScrollBarEnabled(false);
      root.addView(filterScroll, Ui.lp(this, -1, 52));
      LinearLayout chips = Ui.horizontal(this);
      filterScroll.addView(chips);
      ArrayList<String> labels = new ArrayList<>();
      labels.add("All");
      for (Catalog.Episode item : Catalog.forYear(year, "All", true))
        if (!labels.contains(item.show)) labels.add(item.show);
      for (String choice : labels) {
        TextView chip =
            Ui.button(
                this,
                Catalog.showLabel(choice, year),
                filter.equals(choice),
                () -> {
                  filter = choice;
                  render();
                });
        chips.addView(chip);
        Ui.gap(chips, 8);
      }
    }
    if (page.equals("ppv") || page.equals("show")) {
      HorizontalScrollView yearScroll = new HorizontalScrollView(this);
      yearScroll.setHorizontalScrollBarEnabled(false);
      LinearLayout years = Ui.horizontal(this);
      yearScroll.addView(years);
      root.addView(yearScroll, Ui.lp(this, -1, 48));
      TreeSet<Integer> availableYears = new TreeSet<>();
      availableYears.add(0);
      for (Catalog.Episode item : Catalog.episodes)
        if (item.show.equals(page.equals("ppv") ? "PPV" : filter)) availableYears.add(item.year);
      for (int value : availableYears) {
        years.addView(
            Ui.button(
                this,
                value == 0 ? "All years" : Integer.toString(value),
                year == value,
                () -> {
                  year = value;
                  render();
                }));
        Ui.gap(years, 8);
      }
    }
    HorizontalScrollView months = null;
    LinearLayout monthButtons = null;
    if (page.equals("year")) {
      months = new HorizontalScrollView(this);
      months.setHorizontalScrollBarEnabled(false);
      monthButtons = Ui.horizontal(this);
      months.addView(monthButtons);
      root.addView(months, Ui.lp(this, -1, 44));
    }
    Ui.gap(root, 10);
    RecyclerView grid = new RecyclerView(this);
    grid.setLayoutManager(new GridLayoutManager(this, 4));
    grid.setClipToPadding(false);
    grid.setPadding(0, d(3), 0, d(20));
    root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
    grid.setAdapter(new EpisodeAdapter(items, false, page.equals("ppv")));
    if (monthButtons != null) {
      String[] names = {
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
      };
      for (int month = 1; month <= 12; month++) {
        int position = Catalog.firstInMonth(items, month);
        TextView button =
            Ui.button(this, names[month - 1], false, () -> jumpToEpisode(grid, position));
        button.setEnabled(position >= 0);
        button.setFocusable(position >= 0);
        button.setAlpha(position >= 0 ? 1f : .3f);
        button.setContentDescription(
            names[month - 1] + (position < 0 ? ": no available shows" : ": jump to first show"));
        monthButtons.addView(button);
        Ui.gap(monthButtons, 5);
      }
    }
    if (items.isEmpty())
      root.addView(text("No sourced full shows match this selection.", 16, Ui.MUTED));
  }

  void jumpToEpisode(RecyclerView grid, int position) {
    if (position < 0) return;
    GridLayoutManager layout = (GridLayoutManager) grid.getLayoutManager();
    if (layout == null) return;
    grid.stopScroll();
    grid.setPreserveFocusAfterLayout(false);
    grid.clearFocus();
    grid.getViewTreeObserver()
        .addOnGlobalLayoutListener(
            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
              @Override
              public void onGlobalLayout() {
                RecyclerView.ViewHolder card = grid.findViewHolderForAdapterPosition(position);
                if (card == null) return;
                grid.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                card.itemView.setFocusableInTouchMode(true);
                card.itemView.requestFocus();
                grid.setPreserveFocusAfterLayout(true);
              }
            });
    if (BuildConfig.DEBUG) android.util.Log.d("Chronology", "MONTH_JUMP position=" + position);
    layout.scrollToPositionWithOffset(position, 0);
    grid.requestLayout();
  }

  final class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.Holder> {
    final ArrayList<Catalog.Episode> items;
    final boolean horizontal, portrait;
    final Map<String, org.json.JSONObject> communityStats = new HashMap<>();

    EpisodeAdapter(ArrayList<Catalog.Episode> values) {
      this(values, false);
    }

    EpisodeAdapter(ArrayList<Catalog.Episode> values, boolean rail) {
      this(values, rail, false);
    }

    EpisodeAdapter(ArrayList<Catalog.Episode> values, boolean rail, boolean posters) {
      items = values;
      horizontal = rail;
      portrait = posters;
    }

    class Holder extends RecyclerView.ViewHolder {
      ImageView image;
      TextView title, date, source;
      ProgressBar progress;

      Holder(LinearLayout card) {
        super(card);
        FrameLayout artwork = (FrameLayout) card.getChildAt(0);
        image = (ImageView) artwork.getChildAt(0);
        progress = (ProgressBar) artwork.getChildAt(1);
        title = (TextView) card.getChildAt(1);
        date = (TextView) card.getChildAt(2);
        source = (TextView) card.getChildAt(3);
      }
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int type) {
      LinearLayout card = Ui.vertical(MainActivity.this);
      card.setPadding(d(8), d(8), d(8), d(10));
      card.setBackground(Ui.focus(MainActivity.this, false));
      card.setFocusable(true);
      card.setClickable(true);
      RecyclerView.LayoutParams params =
          new RecyclerView.LayoutParams(
              horizontal ? d(portrait ? 160 : 215) : -1, d(portrait ? 290 : 196));
      params.setMargins(d(4), d(4), d(8), d(8));
      card.setLayoutParams(params);
      ImageView image = new ImageView(MainActivity.this);
      image.setScaleType(
          portrait ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
      image.setBackground(Ui.shape(MainActivity.this, Company.current.surface(), 0));
      image.setClipToOutline(true);
      FrameLayout artwork = new FrameLayout(MainActivity.this);
      artwork.addView(image, new FrameLayout.LayoutParams(-1, -1));
      ProgressBar progress =
          new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);
      progress.setMax(100);
      progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.accent()));
      progress.setProgressBackgroundTintList(
          android.content.res.ColorStateList.valueOf(0xb3404040));
      progress.setFocusable(false);
      progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      artwork.addView(progress, new FrameLayout.LayoutParams(-1, d(4), Gravity.BOTTOM));
      card.addView(artwork, Ui.lp(MainActivity.this, -1, portrait ? 206 : 112));
      TextView title = text("", 14, Ui.WHITE);
      title.setTypeface(null, Typeface.BOLD);
      title.setMaxLines(1);
      title.setEllipsize(TextUtils.TruncateAt.END);
      title.setPadding(0, d(9), 0, d(5));
      card.addView(title, Ui.lp(MainActivity.this, -1, 34));
      card.addView(text("", 12, Ui.MUTED), Ui.lp(MainActivity.this, -1, 21));
      card.addView(text("", 10, Ui.MUTED), Ui.lp(MainActivity.this, -1, 17));
      return new Holder(card);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
      Catalog.Episode item = items.get(position);
      Art.loadEpisode(holder.image, item, portrait);
      boolean watched = Catalog.watched(MainActivity.this, item);
      long viewed = Catalog.displayPosition(MainActivity.this, item);
      long duration = Catalog.displayDuration(MainActivity.this, item);
      int percent = Catalog.progressPercent(viewed, duration, watched);
      holder.progress.setVisibility(
          watched || (viewed > 0 && duration > 0) ? View.VISIBLE : View.GONE);
      holder.progress.setProgress(percent);
      holder.title.setText((watched ? "✓  " : "") + item.heading());
      holder.date.setText(Catalog.dateLabel(item.date));
      String progressText =
          watched
              ? "Watched"
              : viewed > 0
                  ? (duration > 0 ? percent + "% watched" : viewed / 60000 + " min watched")
                  : "";
      holder.source.setText(progressText.isEmpty() ? item.sourceLabel() : progressText);
      holder.itemView.setContentDescription(
          item.heading() + ", " + Catalog.dateLabel(item.date) + ", " + progressText);
      FrameLayout artwork = (FrameLayout) ((LinearLayout) holder.itemView).getChildAt(0);
      while (artwork.getChildCount() > 2) artwork.removeViewAt(2);
      org.json.JSONObject stats = communityStats.get(item.id);
      if (stats != null) {
        LinearLayout badge = CommunityPanel.badges(MainActivity.this, stats);
        FrameLayout.LayoutParams badgePosition = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        badgePosition.setMargins(d(7), 0, d(7), d(9));
        artwork.addView(badge, badgePosition);
      }
      holder.itemView.setOnClickListener(
          view -> details(item, horizontal ? Catalog.forYear(item.year, "All", extras()) : items));
    }

    @Override
    public int getItemCount() {
      return items.size();
    }
  }

  Dialog panel(LinearLayout content, int width) {
    Dialog dialog = new Dialog(this);
    content.setPadding(d(26), d(24), d(26), d(24));
    content.setBackground(Ui.shape(this, Company.current.surface(), Color.rgb(49, 56, 74)));
    dialog.setContentView(content);
    dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    dialog.getWindow().setDimAmount(.8f);
    dialog.show();
    dialog.getWindow().setLayout(d(width), -2);
    return dialog;
  }

  void details(Catalog.Episode item, ArrayList<Catalog.Episode> queue) {
    launch(item, queue, null);
  }

  void launch(Catalog.Episode item, ArrayList<Catalog.Episode> queue, String provider) {
    Intent intent = new Intent(this, PlayerActivity.class);
    ArrayList<String> ids = new ArrayList<>();
    for (Catalog.Episode value : queue) ids.add(value.id);
    intent.putStringArrayListExtra("queue", ids);
    intent.putExtra("id", item.id);
    intent.putExtra("company", Company.current.id);
    if (provider != null) intent.putExtra("provider", provider);
    if (BuildConfig.DEBUG && getIntent().getBooleanExtra("verify_autoplay", false)) {
      intent.putExtra("verify_autoplay", true);
      getIntent().removeExtra("verify_autoplay");
    }
    startActivity(intent);
  }

  void search() {
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setHint("Show, event, year or date");
    new AlertDialog.Builder(this)
        .setTitle("Search " + Company.current.name)
        .setView(input)
        .setPositiveButton(
            "Search",
            (dialog, which) -> {
              query = input.getText().toString().trim();
              page = "search";
              render();
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  void setting(LinearLayout content, String name, String value, Runnable action) {
    LinearLayout row = Ui.horizontal(this);
    row.setPadding(d(14), d(12), d(14), d(12));
    row.setBackground(Ui.focus(this, false));
    row.setFocusable(true);
    row.setClickable(true);
    row.setOnClickListener(view -> action.run());
    row.addView(text(name, 14, Ui.WHITE));
    Ui.weight(row);
    TextView selected = text(value + "  ›", 13, Ui.MUTED);
    row.addView(selected);
    content.addView(row, Ui.lp(this, -1, 46));
    Ui.gap(content, 5);
  }

  void showVisibility() {
    SharedPreferences prefs = Catalog.prefs(this);
    java.util.TreeSet<String> names = new java.util.TreeSet<>();
    for (Catalog.Episode item : Catalog.episodes)
      if (!page.equals("year") || item.year == year) names.add(item.show);
    String[] shows = names.toArray(new String[0]);
    boolean[] checked = new boolean[shows.length];
    java.util.Set<String> hidden = new java.util.HashSet<>(Catalog.hiddenShows);
    for (int i = 0; i < shows.length; i++) checked[i] = !hidden.contains(shows[i]);
    new AlertDialog.Builder(this).setTitle("Shows in All · select to include")
        .setMultiChoiceItems(shows, checked, (choice, index, visible) -> {
          if (visible) hidden.remove(shows[index]); else hidden.add(shows[index]);
        }).setPositiveButton("Apply", (choice, which) -> {
          Catalog.hiddenShows = new java.util.HashSet<>(hidden);
          prefs.edit().putStringSet("hidden_shows", hidden).apply();
          if (page.equals("year")) filter = "All";
          render();
        }).setNegativeButton("Cancel", null).show();
  }

  void settings() {
    SharedPreferences prefs = Catalog.prefs(this);
    SharedPreferences playbackPreferences = getSharedPreferences("application", MODE_PRIVATE);
    LinearLayout content = Ui.vertical(this);
    TextView title = text("Settings", 27, Ui.WHITE);
    title.setTypeface(null, Typeface.BOLD);
    content.addView(title);
    Ui.gap(content, 16);
    ScrollView scroll = new ScrollView(this);
    LinearLayout options = Ui.vertical(this);
    scroll.addView(options);
    content.addView(scroll, Ui.lp(this, -1, 330));
    Dialog dialog = panel(content, 680);
    setting(options, "Shows in chronology", Catalog.hiddenShows.size() + " hidden", () -> { dialog.dismiss(); showVisibility(); });
    setting(
        options,
        "Autoplay next episode",
        prefs.getBoolean("autoplay", true) ? "On" : "Off",
        () -> {
          prefs.edit().putBoolean("autoplay", !prefs.getBoolean("autoplay", true)).apply();
          dialog.dismiss();
          settings();
        });
    setting(
        options,
        "Picture size (all companies)",
        playbackPreferences.getBoolean("stretch_picture", false)
            ? "16:9 horizontal stretch"
            : "Original proportions",
        () -> {
          playbackPreferences
              .edit()
              .putBoolean(
                  "stretch_picture", !playbackPreferences.getBoolean("stretch_picture", false))
              .apply();
          dialog.dismiss();
          settings();
        });
    String[] sources = {"Archive first", "YouTube first", "Archive only", "YouTube only"};
    int preference = prefs.getInt("source", 1);
    setting(
        options,
        "Preferred source",
        sources[preference],
        () -> {
          prefs.edit().putInt("source", (preference + 1) % 4).apply();
          dialog.dismiss();
          settings();
        });
    int quality = prefs.getInt("quality", 720);
    setting(
        options,
        "Maximum video quality",
        quality + "p",
        () -> {
          prefs.edit().putInt("quality", quality == 1080 ? 720 : 1080).apply();
          dialog.dismiss();
          settings();
        });
    if (Company.current == Company.WCW)
      setting(
          options,
          "Additional WCW shows",
          extras() ? "Included" : "Hidden",
          () -> {
            prefs.edit().putBoolean("extras", !extras()).apply();
            dialog.dismiss();
            render();
            settings();
          });
    setting(
        options,
        "Skip unavailable episodes",
        prefs.getBoolean("skip", true) ? "On" : "Off",
        () -> {
          prefs.edit().putBoolean("skip", !prefs.getBoolean("skip", true)).apply();
          dialog.dismiss();
          settings();
        });
    setting(
        options,
        "Automatic library refresh",
        prefs.getBoolean("auto_refresh", true) ? "Every 6 hours" : "Off",
        () -> {
          prefs.edit().putBoolean("auto_refresh", !prefs.getBoolean("auto_refresh", true)).apply();
          LibraryJob.schedule(this);
          dialog.dismiss();
          settings();
        });
    setting(
        options,
        "Refresh catalogue",
        "Check GitHub source updates",
        () -> {
          dialog.dismiss();
          refresh(false);
        });
    setting(
        options,
        "Check source updates again",
        "Download changed library data only",
        () -> {
          dialog.dismiss();
          refresh(false, true);
        });
    setting(
        options,
        "Last refresh report",
        "View source checks",
        () -> {
          ScrollView report = new ScrollView(this);
          TextView contentText =
              text(prefs.getString("last_scan_report", "No completed scan yet."), 13, Ui.MUTED);
          contentText.setPadding(d(20), d(16), d(20), d(16));
          report.addView(contentText);
          new AlertDialog.Builder(this)
              .setTitle("Source refresh report")
              .setView(report)
              .setPositiveButton("OK", null)
              .show();
        });
    setting(
        options,
        "Source update feed",
        "GitHub - JSON only",
        () -> {
          EditText input = new EditText(this);
          input.setSingleLine(true);
          SharedPreferences application = getSharedPreferences("application", 0);
          input.setText(application.getString("source_feed", RemoteLibrary.DEFAULT_FEED));
          new AlertDialog.Builder(this)
              .setTitle("Source update feed")
              .setView(input)
              .setPositiveButton(
                  "Save",
                  (box, which) -> {
                    String url = input.getText().toString().trim();
                    if (LibraryPack.https(url))
                      application.edit().putString("source_feed", url).apply();
                    else
                      Toast.makeText(this, "Enter an HTTPS manifest URL", Toast.LENGTH_LONG).show();
                  })
              .setNegativeButton("Cancel", null)
              .show();
        });
    setting(
        options,
        "Update app",
        "v" + BuildConfig.VERSION_NAME,
        () -> {
          dialog.dismiss();
          updater.check(false);
        });
    setting(
        options,
        "Check for updates on launch",
        prefs.getBoolean("auto_update_check", true) ? "On" : "Off",
        () -> {
          prefs
              .edit()
              .putBoolean("auto_update_check", !prefs.getBoolean("auto_update_check", true))
              .apply();
          dialog.dismiss();
          settings();
        });
    setting(
        options,
        "Update feed",
        prefs.getString("update_feed", "").isEmpty() ? "GitHub releases" : "Custom feed",
        () -> {
          EditText input = new EditText(this);
          input.setSingleLine(true);
          input.setText(prefs.getString("update_feed", ""));
          input.setHint("Leave empty for official GitHub releases");
          new AlertDialog.Builder(this)
              .setTitle("App update feed")
              .setView(input)
              .setPositiveButton(
                  "Save",
                  (box, which) ->
                      prefs
                          .edit()
                          .putString("update_feed", input.getText().toString().trim())
                          .apply())
              .setNegativeButton("Cancel", null)
              .show();
        });
    setting(
        options,
        "Playback and storage",
        "Streaming · no video downloads",
        () ->
            new AlertDialog.Builder(this)
                .setMessage(
                    "Videos stream directly to the built-in player. Only artwork, catalogue data"
                        + " and viewing progress are saved. YouTube is played without an account or"
                        + " its advertising player.")
                .setPositiveButton("OK", null)
                .show());
    setting(
        options,
        "Clear viewing history",
        "Reset progress",
        () ->
            new AlertDialog.Builder(this)
                .setTitle("Clear viewing progress?")
                .setMessage("This resets watched markers and resume positions.")
                .setPositiveButton(
                    "Clear",
                    (box, which) -> {
                      SharedPreferences.Editor edit = prefs.edit();
                      for (String key : prefs.getAll().keySet())
                        if (key.startsWith("pos:")
                            || key.startsWith("played:")
                            || key.startsWith("watched:")
                            || key.startsWith("seen:")
                            || key.startsWith("length:")
                            || key.startsWith("partLength:")
                            || key.equals("last")
                            || key.startsWith("last:")
                            || key.startsWith("part:")
                            || key.startsWith("provider:")
                            || key.startsWith("choice:")) edit.remove(key);
                      edit.apply();
                      dialog.dismiss();
                      render();
                    })
                .setNegativeButton("Cancel", null)
                .show());
    setting(
        options,
        "Open-source components",
        "Media3 + NewPipeExtractor",
        () ->
            new AlertDialog.Builder(this)
                .setTitle("Wrestling Chronology")
                .setMessage(
                    "Independent wrestling chronology player.\n\n"
                        + "Playback: AndroidX Media3 (Apache 2.0).\n"
                        + "YouTube extraction: NewPipeExtractor (GPL 3.0 or later).\n"
                        + "Source: supplied alongside this APK.\n\n"
                        + "Videos remain hosted by their original providers.")
                .setNeutralButton("Licences", (box, which) -> licences())
                .setPositiveButton("OK", null)
                .show());
  }

  void refresh(boolean silent) {
    refresh(silent, false);
  }

  void refresh(boolean silent, boolean full) {
    if (CatalogSync.busy) {
      Toast.makeText(this, "The library is already refreshing", Toast.LENGTH_SHORT).show();
      return;
    }
    if (!silent) Toast.makeText(this, "Checking full-show sources…", Toast.LENGTH_SHORT).show();
    work.execute(
        () -> {
          try {
            CatalogSync.refresh(this, full);
            runOnUiThread(
                () -> {
                  refreshWasBusy = false;
                  render();
                  notifyFound(!silent);
                });
          } catch (Exception error) {
            runOnUiThread(
                () -> {
                  updateRefresh();
                  if (!silent)
                    Toast.makeText(
                            this,
                            error instanceof CatalogSync.Deferred
                                ? error.getMessage() + " · refresh resumes when you return"
                                : "Refresh stopped · "
                                    + CatalogSync.reason(error)
                                    + " · saved library kept",
                            Toast.LENGTH_LONG)
                        .show();
                });
          }
        });
  }

  void licences() {
    try {
      String value =
          Catalog.read(getAssets().open("licenses/THIRD_PARTY_NOTICES.txt"))
              + "\n\n"
              + Catalog.read(getAssets().open("licenses/GPL-3.0.txt"))
              + "\n\n"
              + Catalog.read(getAssets().open("licenses/APACHE-2.0.txt"));
      ScrollView scroll = new ScrollView(this);
      TextView text = text(value, 12, Ui.MUTED);
      text.setPadding(d(20), d(16), d(20), d(16));
      scroll.addView(text);
      new AlertDialog.Builder(this)
          .setTitle("Open-source licences")
          .setView(scroll)
          .setPositiveButton("OK", null)
          .show();
    } catch (Exception error) {
      Toast.makeText(
              this, "Licence texts are also included with the source bundle.", Toast.LENGTH_LONG)
          .show();
    }
  }

  void updateRefresh() {
    if (refreshStrip == null) return;
    String error = Catalog.prefs(this).getString("last_refresh_error", "");
    refreshStrip.setVisibility(CatalogSync.busy ? View.VISIBLE : View.GONE);
    refreshText.setText(CatalogSync.busy ? CatalogSync.status : "Last scan skipped: " + error);
    refreshBar.setVisibility(CatalogSync.busy ? View.VISIBLE : View.GONE);
    refreshBar.setMax(Math.max(1, CatalogSync.total));
    refreshBar.setProgress(CatalogSync.completed);
  }

  void checkRefresh() {
    if (!ready || SystemClock.elapsedRealtime() - companyOpenedAt < 30000) return;
    SharedPreferences prefs = Catalog.prefs(this);
    long checked =
        Math.max(prefs.getLong("last_refresh", 0), prefs.getLong("last_refresh_attempt", 0));
    if (prefs.getBoolean("auto_refresh", true)
        && !CatalogSync.busy
        && !PlayerActivity.playbackActive
        && (prefs.getBoolean("refresh_deferred", false)
            || (prefs.getLong("refresh_retry_after", 0) > 0
                && System.currentTimeMillis() > prefs.getLong("refresh_retry_after", 0))
            || System.currentTimeMillis() - checked > CatalogSync.INTERVAL)) refresh(true);
  }

  void notifyFound(boolean always) {
    SharedPreferences prefs = Catalog.prefs(this);
    int episodes = prefs.getInt("pending_episodes", 0),
        sources = prefs.getInt("pending_sources", 0);
    if (always || episodes > 0 || sources > 0) {
      Toast.makeText(
              this,
              episodes > 0
                  ? "Found "
                      + episodes
                      + " new episode"
                      + (episodes == 1 ? "" : "s")
                      + " · "
                      + sources
                      + " new sources"
                  : sources > 0
                      ? "Found " + sources + " new sources for existing shows"
                      : prefs.getInt("last_scan_skipped", 0) > 0
                          ? "Scan complete · no new episodes · "
                              + prefs.getInt("last_scan_skipped", 0)
                              + " source checks skipped"
                          : "Library up to date · no new episodes",
              Toast.LENGTH_LONG)
          .show();
      prefs.edit().remove("pending_episodes").remove("pending_sources").apply();
    }
  }

  @Override
  public void onBackPressed() {
    if (page.equals("companies")) {
      super.onBackPressed();
      return;
    }
    if (page.equals("home")) {
      companies();
      return;
    }
    if (!page.equals("home")) {
      page = page.equals("year") ? "years" : "home";
      render();
    } else super.onBackPressed();
  }

  @Override
  protected void onDestroy() {
    updater.destroy();
    uiHandler.removeCallbacksAndMessages(null);
    super.onDestroy();
    work.shutdownNow();
  }
}
