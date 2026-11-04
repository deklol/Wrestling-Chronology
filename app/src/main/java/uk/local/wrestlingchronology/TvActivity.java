package uk.local.wrestlingchronology;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** The TV guide owns its player and controls; company playback is independent. */
public final class TvActivity extends Activity implements TvPlayback.Listener {
  final Handler ui = new Handler(Looper.getMainLooper());
  final ExecutorService io = Executors.newSingleThreadExecutor();
  FrameLayout screen, playerBox, surface, guideHost;
  ScrollView page;
  LinearLayout content, overlay, controls, header;
  TextView notice,
      miniChannel,
      miniTitle,
      miniMeta,
      miniDescription,
      miniClock,
      guideStatus,
      aspect;
  ImageView miniArt;
  SeekBar seek;
  TvApi api;
  TvPlayback playback;
  TvGuide guide, liveGuide;
  TvGuide.Slot preview, failed;
  String tuningChannel, group = "All";
  boolean seeking, destroyed, touchControls, guideOpen = true;
  View firstChannelButton;
  Dialog help;
  boolean guideHelpShown;
  int playerHeight, guideRequest;
  long errorGeneration, lastSaved, lastGuideCheck, lastBackAt;
  Company origin;
  long lastDiagnostic;
  final Runnable hideOverlay = () -> hideOverlay();
  final Runnable ticker =
      new Runnable() {
        public void run() {
          if (destroyed) return;
          if (preview != null && overlay.getVisibility() == View.VISIBLE) {
            miniClock.setText(clock(api.now()));
          }
          if (playback.active != null && !seeking) {
            long duration = playback.active.getDuration();
            if (duration > 0 && overlay.getVisibility() == View.VISIBLE)
              seek.setProgress((int) (playback.active.getCurrentPosition() * 1000 / duration));
            if (SystemClock.elapsedRealtime() - lastSaved > 5000) savePosition();
            if (BuildConfig.DEBUG
                && getIntent().getBooleanExtra("test_diagnostics", false)
                && SystemClock.elapsedRealtime() - lastDiagnostic > 5000) {
              lastDiagnostic = SystemClock.elapsedRealtime();
              androidx.media3.exoplayer.DecoderCounters counters =
                  playback.active.getVideoDecoderCounters();
              android.util.Log.i(
                  "ChronologyTV",
                  "SAMPLE channel="
                      + playback.playing.channel.id
                      + " position="
                      + playback.active.getCurrentPosition()
                      + " buffered="
                      + playback.active.getTotalBufferedDuration()
                      + " playing="
                      + playback.active.isPlaying()
                      + " audio="
                      + (playback.active.getAudioFormat() != null)
                      + " height=" + playback.quality
                      + " dropped="
                      + (counters == null ? -1 : counters.droppedBufferCount));
            }
          }
          if (guide != null && SystemClock.elapsedRealtime() - lastGuideCheck > 15 * 60 * 1000L)
            loadGuide(0, false);
          ui.postDelayed(this, 1000);
        }
      };

  static int translucent(int color, int alpha) {
    return (color & 0x00ffffff) | (alpha << 24);
  }

  int d(float value) {
    return Ui.dp(this, value);
  }

  TextView text(String value, int size, int color) {
    return Ui.text(this, value, size, color);
  }

  String clock(double seconds) {
    return new SimpleDateFormat("HH:mm", Locale.getDefault())
        .format(new Date((long) (seconds * 1000)));
  }

  String date(double seconds) {
    return new SimpleDateFormat("EEE d MMM", Locale.getDefault())
        .format(new Date((long) (seconds * 1000)));
  }

  android.content.SharedPreferences prefs() {
    return getSharedPreferences("tv", MODE_PRIVATE);
  }

  TextView button(String label, Runnable action) {
    TextView view = Ui.button(this, label, false, action);
    StateListDrawable state = new StateListDrawable();
    state.addState(
        new int[] {android.R.attr.state_focused},
        Ui.gradient(this, origin.primary(), origin.secondary(), Ui.WHITE));
    state.addState(new int[] {}, Ui.shape(this, origin.surface(), 0));
    view.setBackground(state);
    view.setTextSize(12);
    view.setPadding(d(12), d(9), d(12), d(9));
    view.setId(View.generateViewId());
    return view;
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().getDecorView().setSystemUiVisibility(5894);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    Company.restore(this);
    origin = Company.current;
    touchControls = getPackageManager().hasSystemFeature("android.hardware.touchscreen");
    api = new TvApi(this);
    screen = new FrameLayout(this);
    screen.setBackgroundColor(origin.background());
    setContentView(screen);
    page = new ScrollView(this);
    page.setFillViewport(true);
    page.setClipToPadding(false);
    screen.addView(page, new FrameLayout.LayoutParams(-1, -1));
    content = Ui.vertical(this);
    content.setPadding(d(30), d(14), d(30), d(20));
    page.addView(content);
    buildHeader();
    playerHeight =
        Math.min(
            (getResources().getDisplayMetrics().widthPixels - d(60)) * 9 / 16,
            getResources().getDisplayMetrics().heightPixels - d(112));
    buildPlayer();
    playback = new TvPlayback(this, api, surface, this);
    playback.muted = BuildConfig.DEBUG && getIntent().getBooleanExtra("test_muted", false);
    playback.stretched = prefs().getBoolean("stretch", false);
    aspect.setText(playback.stretched ? "16:9" : "4:3");
    buildGuideTools();
    guideHost = new FrameLayout(this);
    content.addView(guideHost, new LinearLayout.LayoutParams(-1, -2));
    guideStatus = text("Loading the TV guide...", 12, Ui.MUTED);
    content.addView(guideStatus);
    TextView footer =
        text(
            "STREAMING ONLY · YouTube + Archive.org + Dailymotion + OK.ru\nx.com/digitalm1nd",
            10,
            Ui.MUTED);
    footer.setGravity(Gravity.CENTER);
    footer.setPadding(0, d(28), 0, d(8));
    content.addView(footer);
    loadGuide(0, true);
    // The guide is an overlay over this same player, including on first entry.
    content.removeView(playerBox);
    screen.addView(playerBox, 0, new FrameLayout.LayoutParams(-1, -1));
    page.setBackgroundColor(translucent(origin.background(), 184));
    ui.post(ticker);
  }

  void buildHeader() {
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    scroll.setFillViewport(true);
    content.addView(scroll, Ui.lp(this, -1, 54));
    header = Ui.horizontal(this);
    scroll.addView(header, new FrameLayout.LayoutParams(-1, -1));
    ImageView logo = new ImageView(this);
    origin.setLogo(logo);
    logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
    logo.setFocusable(true);
    logo.setOnClickListener(v -> leave("home"));
    header.addView(logo, Ui.lp(this, 58, 40));
    Ui.gap(header, 12);
    String[] labels = {
      "Companies",
      "Home",
      "Years",
      "Weeklies",
      "PPVs",
      "TV",
      "Trending",
      "History",
      "Donate",
      "Settings"
    };
    String[] targets = {
      "companies",
      "home",
      "years",
      "weeklies",
      "ppv",
      "tv",
      "trending",
      "history",
      "donate",
      "settings"
    };
    for (int i = 0; i < labels.length; i++) {
      final String target = targets[i];
      TextView item =
          button(
              labels[i],
              () -> {
                if (target.equals("tv")) setGuideOpen(false);
                else leave(target);
              });
      item.setTextSize(11);
      item.setPadding(d(9), d(10), d(9), d(10));
      if (target.equals("tv")) item.setTextColor(origin.accent());
      header.addView(item);
      if (i == 0) Ui.weight(header);
      Ui.gap(header, 7);
    }
    header.addView(Ui.searchButton(this, () -> leave("search")), Ui.lp(this, 40, 40));
  }

  void leave(String target) {
    setResult(RESULT_OK, new Intent().putExtra("tv_page", target));
    finish();
  }

  void buildPlayer() {
    playerBox = new FrameLayout(this);
    playerBox.setBackgroundColor(Color.BLACK);
    playerBox.setFocusable(true);
    playerBox.setId(View.generateViewId());
    playerBox.setClipToOutline(true);
    playerBox.setBackground(Ui.shape(this, Color.BLACK, 0));
    content.addView(playerBox, new LinearLayout.LayoutParams(-1, playerHeight));
    surface = new FrameLayout(this);
    playerBox.addView(surface, new FrameLayout.LayoutParams(-1, -1));
    notice = text("Loading the TV guide...", 17, Ui.WHITE);
    notice.setGravity(Gravity.CENTER);
    notice.setPadding(d(20), d(14), d(20), d(14));
    notice.setBackground(Ui.shape(this, translucent(origin.surface(), 230), 0));
    playerBox.addView(notice, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    overlay = Ui.vertical(this);
    overlay.setPadding(d(18), d(12), d(18), d(12));
    overlay.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {translucent(origin.surface(), 237), translucent(origin.background(), 245)}));
    playerBox.addView(
        overlay, new FrameLayout.LayoutParams(-1, d(touchControls ? 232 : 190), Gravity.BOTTOM));
    LinearLayout top = Ui.horizontal(this);
    overlay.addView(top, Ui.lp(this, -1, touchControls ? 44 : 23));
    miniChannel = text("WCtv", 16, Ui.WHITE);
    miniChannel.setTypeface(null, Typeface.BOLD);
    miniChannel.setMaxLines(1);
    miniChannel.setEllipsize(TextUtils.TruncateAt.END);
    top.addView(miniChannel, new LinearLayout.LayoutParams(0, -2, 1));
    if (touchControls) {
      top.addView(button("Channel −", () -> previewChannel(-1)), Ui.lp(this, -2, 44));
      Ui.gap(top, 6);
      top.addView(button("Watch", this::watchPreview), Ui.lp(this, -2, 44));
      Ui.gap(top, 6);
      top.addView(button("Channel +", () -> previewChannel(1)), Ui.lp(this, -2, 44));
    }
    miniClock = text("", 12, Ui.MUTED);
    if (!touchControls) top.addView(miniClock);
    LinearLayout row = Ui.horizontal(this);
    overlay.addView(row, Ui.lp(this, -1, 76));
    miniArt = new ImageView(this);
    miniArt.setScaleType(ImageView.ScaleType.FIT_CENTER);
    miniArt.setBackgroundColor(Color.BLACK);
    row.addView(miniArt, Ui.lp(this, 126, 70));
    Ui.gap(row, 14);
    LinearLayout copy = Ui.vertical(this);
    row.addView(copy, new LinearLayout.LayoutParams(0, -1, 1));
    miniTitle = text("Loading your channel", 21, Ui.WHITE);
    miniTitle.setTypeface(null, Typeface.BOLD);
    miniTitle.setMaxLines(1);
    miniTitle.setEllipsize(TextUtils.TruncateAt.END);
    copy.addView(miniTitle);
    miniMeta = text("", 11, origin.accent());
    miniMeta.setPadding(0, d(5), 0, d(4));
    copy.addView(miniMeta);
    miniDescription = text("", 12, Ui.MUTED);
    miniDescription.setMaxLines(1);
    miniDescription.setEllipsize(TextUtils.TruncateAt.END);
    copy.addView(miniDescription);
    controls = Ui.horizontal(this);
    controls.setPadding(0, d(8), 0, 0);
    if (touchControls) {
      HorizontalScrollView buttons = new HorizontalScrollView(this);
      buttons.setHorizontalScrollBarEnabled(false);
      buttons.addView(controls, new FrameLayout.LayoutParams(-2, -1));
      overlay.addView(buttons, Ui.lp(this, -1, 46));
    } else overlay.addView(controls, Ui.lp(this, -1, 46));
    controls.addView(
        button(
            "❚❚ / ▶",
            () -> {
              playback.pause();
              showOverlay();
            }));
    Ui.gap(controls, 6);
    controls.addView(
        button(
            "−15s",
            () -> {
              playback.seek(-15000);
              showOverlay();
            }));
    Ui.gap(controls, 6);
    controls.addView(
        button(
            "+30s",
            () -> {
              playback.seek(30000);
              showOverlay();
            }));
    Ui.gap(controls, 6);
    seek = new SeekBar(this);
    seek.setMax(1000);
    seek.setFocusable(false);
    seek.setProgressTintList(android.content.res.ColorStateList.valueOf(origin.accent()));
    seek.setThumbTintList(android.content.res.ColorStateList.valueOf(origin.accent()));
    seek.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar bar) {
            seeking = true;
            showOverlay();
          }

          public void onStopTrackingTouch(SeekBar bar) {
            seeking = false;
            showOverlay();
          }

          public void onProgressChanged(SeekBar bar, int position, boolean user) {
            if (user && playback.active != null) {
              long duration = playback.active.getDuration();
              if (duration > 0) playback.active.seekTo(duration * position / 1000);
              showOverlay();
            }
          }
        });
    aspect =
        button(
            "4:3",
            () -> {
              playback.stretch(!playback.stretched);
              prefs().edit().putBoolean("stretch", playback.stretched).apply();
              aspect.setText(playback.stretched ? "16:9" : "4:3");
              showOverlay();
            });
    controls.addView(aspect);
    Ui.gap(controls, 6);
    controls.addView(button("Help", this::showHelp));
    Ui.gap(controls, 6);
    controls.addView(button("Guide", this::focusGuide));
    Ui.gap(controls, 6);
    if (touchControls) overlay.addView(seek, Ui.lp(this, -1, 34));
    else controls.addView(seek, new LinearLayout.LayoutParams(0, d(34), 1));
    TextView hint =
        text(
            "↑ / ↓ Browse channels     ← / → Browse programmes     OK Watch / controls     Menu"
                + " TV guide",
            10,
            Ui.MUTED);
    hint.setPadding(0, d(5), 0, 0);
    if (!touchControls) overlay.addView(hint);
    overlay.setVisibility(View.INVISIBLE);
    playerBox.setOnClickListener(
        v -> {
          playerBox.requestFocus();
          if (touchControls && overlay.getVisibility() == View.VISIBLE && overlay.getAlpha() > .5f)
            hideOverlay();
          else showOverlay();
        });
    playerBox.setOnHoverListener(
        (v, event) -> {
          if (event.getAction() == MotionEvent.ACTION_HOVER_EXIT) hideOverlay();
          else showOverlay();
          return true;
        });
    playerBox.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          if (playback != null && (r-l != or-ol || b-t != ob-ot)) playback.resize();
        });
  }

  void buildGuideTools() {
    Ui.gap(content, 18);
    LinearLayout heading = Ui.horizontal(this);
    content.addView(heading);
    TextView title = text("Live TV guide", 24, Ui.WHITE);
    title.setTypeface(null, Typeface.BOLD);
    heading.addView(title);
    Ui.weight(heading);
    if (touchControls) {
      heading.addView(button("Close guide", () -> setGuideOpen(false)), Ui.lp(this, -2, 44));
      Ui.gap(content, 8);
      HorizontalScrollView days = new HorizontalScrollView(this);
      days.setHorizontalScrollBarEnabled(false);
      content.addView(days);
      heading = Ui.horizontal(this);
      days.addView(heading);
    }
    heading.addView(
        button(
            "‹ Earlier",
            () -> loadGuide((guide == null ? api.now() : guide.start) - 86400, false)));
    Ui.gap(heading, 7);
    heading.addView(button("Now", () -> loadGuide(0, false)));
    Ui.gap(heading, 7);
    heading.addView(
        button(
            "Later ›", () -> loadGuide((guide == null ? api.now() : guide.start) + 86400, false)));
    Ui.gap(heading, 7);
    heading.addView(button("Day", this::chooseDay));
    Ui.gap(content, 8);
  }

  void chooseDay() {
    if (guide == null) return;
    ArrayList<Double> days = new ArrayList<>();
    ArrayList<String> labels = new ArrayList<>();
    Calendar day = Calendar.getInstance();
    day.setTimeInMillis((long) (guide.oldest * 1000));
    day.set(Calendar.HOUR_OF_DAY, 0);
    day.set(Calendar.MINUTE, 0);
    day.set(Calendar.SECOND, 0);
    day.set(Calendar.MILLISECOND, 0);
    while (day.getTimeInMillis() / 1000.0 < guide.expires) {
      days.add(day.getTimeInMillis() / 1000.0);
      labels.add(date(day.getTimeInMillis() / 1000.0));
      day.add(Calendar.DATE, 1);
    }
    new AlertDialog.Builder(this)
        .setTitle("Guide day")
        .setItems(
            labels.toArray(new String[0]), (dialog, which) -> loadGuide(days.get(which), false))
        .show();
  }

  void loadGuide(double start, boolean autoplay) {
    loadGuide(start, autoplay, false);
  }

  void loadGuide(double start, boolean autoplay, boolean automatic) {
    lastGuideCheck = SystemClock.elapsedRealtime();
    int request = ++guideRequest;
    guideStatus.setText("Updating TV guide...");
    io.execute(
        () -> {
          try {
            if (autoplay) api.request("/api/time", null);
            double at = start == 0 ? Math.floor(api.now() / 1800) * 1800 : start;
            if (guide != null) at = Math.max(guide.oldest, Math.min(at, guide.expires - 86400));
            TvGuide result = new TvGuide(api.request("/api/guide?start=" + at, null));
            final double playbackTime = Math.max(at, api.now());
            ui.post(
                () -> {
                  if (destroyed || request != guideRequest) return;
                  guide = result;
                  if (result.start <= api.now() && result.end > api.now()) liveGuide = result;
                  renderGuide();
                  guideStatus.setText(
                      "Times shown in "
                          + TimeZone.getDefault().getDisplayName()
                          + " · Select any programme to watch");
                  if (guideOpen && firstChannelButton != null) firstChannelButton.requestFocus();
                  if (autoplay) {
                    String testProgramme = BuildConfig.DEBUG ? getIntent().getStringExtra("test_programme") : null;
                    if (testProgramme != null) {
                      for (TvGuide.Channel channel : guide.channels) for (TvGuide.Slot slot : channel.slots) {
                        if (testProgramme.equals(slot.episode)) { tune(slot, false); return; }
                      }
                    }
                    double watchAt = playbackTime;
                    String requested = BuildConfig.DEBUG ? getIntent().getStringExtra("test_channel") : null;
                    TvGuide.Channel selected = guide.channel(requested == null
                        ? prefs().getString("channel", "wwe") : requested);
                    if (selected == null || selected.at(watchAt) == null)
                      for (TvGuide.Channel c : guide.channels)
                        if (c.at(watchAt) != null) {
                          selected = c;
                          break;
                        }
                    if (selected != null && selected.at(watchAt) != null)
                      tune(selected.at(watchAt), true, automatic);
                    else notice("No current programmes are available.");
                  }
                });
          } catch (Exception error) {
            ui.post(
                () -> {
                  if (!destroyed && request == guideRequest) {
                    guideStatus.setText("Could not load the guide. Select Now to retry.");
                    notice("");
                  }
                });
          }
        });
  }

  void renderGuide() {
    guideHost.removeAllViews();
    firstChannelButton = null;
    LinearLayout layout = Ui.vertical(this);
    guideHost.addView(layout);
    HorizontalScrollView filters = new HorizontalScrollView(this);
    LinearLayout filterRow = Ui.horizontal(this);
    filters.addView(filterRow);
    layout.addView(filters);
    LinkedHashSet<String> groups = new LinkedHashSet<>();
    groups.add("All");
    for (TvGuide.Channel c : guide.channels) groups.add(c.group);
    for (String value : groups) {
      TextView item =
          button(
              value,
              () -> {
                group = value;
                renderGuide();
              });
      if (value.equals(group)) item.setTextColor(origin.accent());
      filterRow.addView(item);
      Ui.gap(filterRow, 6);
    }
    Ui.gap(layout, 10);
    LinearLayout rows = Ui.horizontal(this);
    rows.setGravity(Gravity.TOP);
    layout.addView(rows);
    LinearLayout labels = Ui.vertical(this);
    rows.addView(labels, Ui.lp(this, 144, -2));
    HorizontalScrollView horizontal = new HorizontalScrollView(this);
    horizontal.setHorizontalScrollBarEnabled(true);
    rows.addView(horizontal, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout timeline = Ui.vertical(this);
    horizontal.addView(timeline, new FrameLayout.LayoutParams(d(5760), -2));
    TextView day = text(date(guide.start), 12, origin.accent());
    day.setGravity(Gravity.CENTER_VERTICAL);
    labels.addView(day, Ui.lp(this, -1, 34));
    FrameLayout ruler = new FrameLayout(this);
    timeline.addView(ruler, Ui.lp(this, -1, 34));
    for (int i = 0; i < 48; i++) {
      TextView label = text(clock(guide.start + i * 1800), 11, Ui.MUTED);
      FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(d(120), d(30));
      bounds.leftMargin = d(i * 120);
      ruler.addView(label, bounds);
    }
    boolean firstRow = true;
    for (TvGuide.Channel channel : guide.channels) {
      if (!group.equals("All") && !group.equals(channel.group)) continue;
      TextView channelButton =
          button(
              channel.number + "  " + channel.name,
              () -> {
                TvGuide.Slot slot = channel.at(api.now());
                if (slot != null) {
                  setGuideOpen(false);
                  tune(slot, true);
                } else
                  Toast.makeText(this, "Select a scheduled programme to watch", Toast.LENGTH_SHORT)
                      .show();
              });
      channelButton.setGravity(Gravity.CENTER_VERTICAL);
      channelButton.setTextSize(12);
      if (firstRow) firstChannelButton = channelButton;
      LinearLayout.LayoutParams rowBounds = Ui.lp(this, -1, 64);
      rowBounds.setMargins(0, 0, d(5), d(4));
      labels.addView(channelButton, rowBounds);
      FrameLayout row = new FrameLayout(this);
      LinearLayout.LayoutParams timelineBounds = Ui.lp(this, -1, 64);
      timelineBounds.bottomMargin = d(4);
      timeline.addView(row, timelineBounds);
      for (TvGuide.Slot slot : channel.slots) {
        double from = Math.max(guide.start, slot.start), to = Math.min(guide.end, slot.end);
        if (to <= from) continue;
        TextView block =
            button(
                slot.title + "\n" + clock(slot.start) + " – " + clock(slot.end),
                () -> {
                  boolean live = slot.start <= api.now() && slot.end > api.now();
                  setGuideOpen(false);
                  tune(slot, live);
                });
        block.setTextSize(12);
        block.setGravity(Gravity.CENTER_VERTICAL);
        block.setMaxLines(2);
        block.setEllipsize(TextUtils.TruncateAt.END);
        if (slot.start <= api.now() && slot.end > api.now()) block.setTextColor(origin.accent());
        FrameLayout.LayoutParams bounds =
            new FrameLayout.LayoutParams(
                Math.max(d(8), d((float) ((to - from) / 15)) - d(4)), d(64));
        bounds.leftMargin = d((float) ((from - guide.start) / 15));
        row.addView(block, bounds);
      }
      firstRow = false;
    }
  }

  void tune(TvGuide.Slot slot, boolean live) {
    tune(slot, live, false);
  }

  void tune(TvGuide.Slot slot, boolean live, boolean automatic) {
    savePosition();
    failed = null;
    errorGeneration++;
    tuningChannel = slot.channel.id;
    preview = slot;
    playback.tune(slot, live ? slot.offset(api.now()) : 0, live, automatic);
    showOverlay();
  }

  void previewChannel(int direction) {
    TvGuide guide = liveGuide;
    if (guide == null || guide.channels.isEmpty()) return;
    String current =
        preview != null
            ? preview.channel.id
            : playback.playing == null ? "" : playback.playing.channel.id;
    int index = 0;
    for (int i = 0; i < guide.channels.size(); i++)
      if (guide.channels.get(i).id.equals(current)) {
        index = i;
        break;
      }
    for (int i = 1; i <= guide.channels.size(); i++) {
      TvGuide.Channel next =
          guide.channels.get(Math.floorMod(index + direction * i, guide.channels.size()));
      TvGuide.Slot slot = next.at(api.now());
      if (slot != null) {
        preview = slot;
        showOverlay();
        return;
      }
    }
  }

  void browse(int direction) {
    if (preview == null || guide == null) return;
    TvGuide.Channel channel = preview.channel;
    if (channel == null) return;
    int index = 0;
    for (int i = 0; i < channel.slots.size(); i++)
      if (channel.slots.get(i).id.equals(preview.id)) {
        index = i;
        break;
      }
    int next = index + direction;
    if (next >= 0 && next < channel.slots.size()) preview = channel.slots.get(next);
    showOverlay();
  }

  void watchPreview() {
    if (preview == null) return;
    if (playback.playing == null || !preview.id.equals(playback.playing.id))
      tune(preview, preview.start <= api.now() && preview.end > api.now());
    else hideOverlay();
  }

  void showOverlay() {
    if (preview == null || guideOpen) return;
    overlay.animate().cancel();
    overlay.setAlpha(1);
    overlay.setVisibility(View.VISIBLE);
    miniChannel.setText(preview.channel.number + "  " + preview.channel.name);
    miniTitle.setText(preview.title);
    miniMeta.setText(
        clock(preview.start)
            + " – "
            + clock(preview.end)
            + " · "
            + Math.round((preview.end - preview.start) / 60)
            + " min · "
            + preview.date
            + (playback.playing != null && preview.id.equals(playback.playing.id)
                ? " · " + playback.quality + "p"
                : ""));
    miniDescription.setText(preview.description);
    String image = preview.artwork;
    if (!image.equals(miniArt.getTag(R.id.tv_art_identity))) {
      miniArt.setTag(R.id.tv_art_identity, image);
      if (image.startsWith("https://")) Art.load(miniArt, image);
      else miniArt.setImageDrawable(null);
    }
    miniClock.setText(clock(api.now()));
    ui.removeCallbacks(hideOverlay);
    ui.postDelayed(hideOverlay, 4000);
  }

  void hideOverlay() {
    ui.removeCallbacks(hideOverlay);
    if (inside(getCurrentFocus(), overlay)) playerBox.requestFocus();
    overlay
        .animate()
        .alpha(0)
        .setDuration(1000)
        .withEndAction(() -> overlay.setVisibility(View.INVISIBLE))
        .start();
  }

  boolean inside(View view, View parent) {
    while (view != null) {
      if (view == parent) return true;
      android.view.ViewParent next = view.getParent();
      view = next instanceof View ? (View) next : null;
    }
    return false;
  }

  void options() {
    TvGuide.Slot slot = preview != null ? preview : playback.playing;
    if (slot == null) return;
    ui.removeCallbacks(hideOverlay);
    String[] choices = {
      "Watch this programme from the start",
      "Continue saved position",
      "Return to live channel",
      "Next programme",
      "Retry channel",
      "TV guide",
      "Remote controls"
    };
    new AlertDialog.Builder(this)
        .setTitle(slot.title)
        .setItems(
            choices,
            (dialog, which) -> {
              if (which == 0) tune(slot, false);
              else if (which == 1) {
                tuningChannel = slot.channel.id;
                playback.tune(slot, prefs().getLong("position:" + slot.programme, 0));
              } else if (which == 2) {
                TvGuide.Channel c = liveGuide == null ? null : liveGuide.channel(slot.channel.id);
                if (c != null && c.at(api.now()) != null) tune(c.at(api.now()), true);
              } else if (which == 3) {
                browse(1);
                if (preview != null) tune(preview, false);
              } else if (which == 4) tune(failed != null ? failed : slot, true);
              else if (which == 5) focusGuide();
              else showHelp();
              showOverlay();
            })
        .setOnDismissListener(dialog -> showOverlay())
        .show();
  }

  void focusGuide() {
    setGuideOpen(true);
  }

  void setGuideOpen(boolean value) {
    guideOpen = value;
    page.setVisibility(value ? View.VISIBLE : View.GONE);
    if (value) {
      overlay.animate().cancel();
      overlay.setVisibility(View.INVISIBLE);
      ui.removeCallbacks(hideOverlay);
      if (firstChannelButton != null) firstChannelButton.requestFocus();
      maybeShowGuideHelp();
    } else {
      playerBox.requestFocus();
      showOverlay();
    }
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    if (playback == null) return super.dispatchKeyEvent(event);
    boolean inPlayer = !guideOpen;
    int key = event.getKeyCode();
    if (event.getAction() == KeyEvent.ACTION_DOWN && key != KeyEvent.KEYCODE_BACK) lastBackAt = 0;
    if (key == KeyEvent.KEYCODE_MENU) {
      if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
        if (guideOpen && playback.active != null) setGuideOpen(false);
        else focusGuide();
      }
      return true;
    }
    if (event.getAction() == KeyEvent.ACTION_DOWN && inPlayer) {
      if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
        if (event.getRepeatCount() == 0 || event.getRepeatCount() % 4 == 0)
          previewChannel(key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
        playerBox.requestFocus();
        return true;
      }
      if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
        playback.pause();
        showOverlay();
        return true;
      }
      if (key == KeyEvent.KEYCODE_MEDIA_REWIND) {
        playback.seek(-15000);
        showOverlay();
        return true;
      }
      if (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
        playback.seek(30000);
        showOverlay();
        return true;
      }
      if (getCurrentFocus() == playerBox) {
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
          browse(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
          return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
          if (event.getRepeatCount() == 0) {
            if (failed != null
                && playback.active == null
                && (preview == null || preview.id.equals(failed.id))) tune(failed, true);
            else if (preview != null
                && (playback.playing == null || !preview.id.equals(playback.playing.id))
                && (playback.requested == null || !preview.id.equals(playback.requested.id)))
              tune(preview, preview.start <= api.now() && preview.end > api.now());
            else {
              showOverlay();
              controls.getChildAt(0).requestFocus();
            }
          }
          return true;
        }
      } else showOverlay();
    }
    return super.dispatchKeyEvent(event);
  }

  @Override
  public void onBackPressed() {
    long now = SystemClock.elapsedRealtime();
    if (lastBackAt != 0 && now - lastBackAt < 2500) {
      leave("home");
      return;
    }
    lastBackAt = now;
    if (guideOpen) setGuideOpen(false);
    playerBox.requestFocus();
    hideOverlay();
    Toast.makeText(
            this, "Press back again to return to " + origin.name + " home", Toast.LENGTH_SHORT)
        .show();
  }

  public void notice(String message) {
    notice.setText(message);
    notice.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
  }

  public void playing(TvGuide.Slot slot) {
    failed = null;
    tuningChannel = null;
    if (preview == null || preview.id.equals(slot.id)) preview = slot;
    errorGeneration++;
    remember(slot);
    showOverlay();
  }

  void remember(TvGuide.Slot slot) {
    android.content.SharedPreferences.Editor editor =
        prefs().edit().putString("channel", slot.channel.id);
    try {
      org.json.JSONArray previous = new org.json.JSONArray(prefs().getString("recent", "[]"));
      org.json.JSONArray recent = new org.json.JSONArray().put(slot.programme);
      for (int i = 0; i < previous.length(); i++) {
        String id = previous.getString(i);
        if (id.equals(slot.programme)) continue;
        if (recent.length() < 200) recent.put(id);
        else editor.remove("position:" + id);
      }
      editor.putString("recent", recent.toString());
    } catch (org.json.JSONException ignored) {
      editor.remove("recent");
    }
    editor.apply();
  }

  void maybeShowGuideHelp() {
    if (!guideHelpShown && !prefs().getBoolean("hideGuideHelp", false)) showHelp();
  }

  void showHelp() {
    if (isFinishing() || destroyed || (help != null && help.isShowing())) return;
    guideHelpShown = true;
    ui.removeCallbacks(hideOverlay);
    View previousFocus = getCurrentFocus();
    help = new Dialog(this);
    LinearLayout panel = Ui.vertical(this);
    panel.setPadding(d(24), d(20), d(24), d(20));
    panel.setBackground(Ui.gradient(this, origin.surface(), origin.background(), 0));
    TextView title = text("Make the TV guide yours", 24, Ui.WHITE);
    title.setTypeface(null, Typeface.BOLD);
    panel.addView(title);
    Ui.gap(panel, 10);
    TextView subtitle = text("Watch now, jump ahead or revisit an earlier show.", 14, origin.accent());
    panel.addView(subtitle);
    Ui.gap(panel, 12);
    ScrollView scroll = new ScrollView(this);
    scroll.setFocusable(true);
    LinearLayout instructions = Ui.vertical(this);
    scroll.addView(instructions);
    String[] lines = touchControls ? new String[] {
      "Guide opens the schedule over your playing channel. Tap any show to watch it.",
      "Swipe left or right for earlier and upcoming shows. You can play future listings now.",
      "Earlier / Later move through days. Day jumps to a date; Now returns to today's schedule.",
      "Tap the picture for player controls. Preview channels, then tap Watch to switch.",
      "Drag the seek bar to rewind or skip ahead. Help opens these tips at any time."
    } : new String[] {
      "In the guide: use Up / Down for channels, Left / Right for shows, then OK to watch.",
      "Any listing can play now, including earlier shows and shows scheduled for later.",
      "Earlier / Later move back or forward a day. Day jumps to a date; Now returns to today's schedule.",
      "While watching: Up / Down preview channels; Left / Right preview shows. Press OK to switch.",
      "Menu opens or closes the guide. Back hides it; press Back again to return home.",
      "Press OK on the current show for player controls. Help reopens these tips."
    };
    for (String instruction : lines) {
      TextView line = text(instruction, 14, Ui.WHITE);
      line.setPadding(0, d(6), 0, d(6));
      instructions.addView(line);
    }
    panel.addView(scroll, new LinearLayout.LayoutParams(-1,
        Math.max(d(80), Math.min(d(228), getResources().getDisplayMetrics().heightPixels - d(240)))));
    Ui.gap(panel, 12);
    LinearLayout actions = Ui.horizontal(this);
    TextView done = button("Got it", () -> help.dismiss());
    actions.addView(done);
    Ui.gap(actions, 10);
    actions.addView(button("Don't show again", () -> {
      prefs().edit().putBoolean("hideGuideHelp", true).apply();
      help.dismiss();
    }));
    panel.addView(actions);
    Ui.gap(panel, 8);
    panel.addView(button("Playback options", () -> { help.dismiss(); options(); }), Ui.lp(this, -2, -2));
    help.setContentView(panel);
    help.setOnDismissListener(dialog -> {
      if (destroyed || isFinishing()) return;
      if (previousFocus != null && previousFocus.isShown()) previousFocus.requestFocus();
      else if (guideOpen && firstChannelButton != null) firstChannelButton.requestFocus();
      else playerBox.requestFocus();
      if (!guideOpen) showOverlay();
    });
    help.show();
    help.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    help.getWindow().setLayout(Math.min(d(640), getResources().getDisplayMetrics().widthPixels - d(32)), -2);
    done.requestFocus();
  }

  public void failed(TvGuide.Slot slot, String message) {
    failed = slot;
    tuningChannel = null;
    long token = ++errorGeneration;
    notice(
        message
            + (playback.active != null
                ? ". Staying on your current channel."
                : ". Press OK to retry or browse channels with ↑ / ↓, then OK."));
    ui.postDelayed(
        () -> {
          if (token == errorGeneration) notice("");
        },
        4000);
  }

  public void ended(TvGuide.Slot slot) {
    if (guide == null) return;
    TvGuide.Channel c = slot.channel;
    if (c == null) return;
    for (TvGuide.Slot next : c.slots)
      if (next.start >= slot.end - 0.1) {
        tune(next, false, true);
        return;
      }
    loadGuide(slot.end, true, true);
  }

  void savePosition() {
    if (playback == null || playback.active == null || playback.playing == null) return;
    prefs()
        .edit()
        .putLong("position:" + playback.playing.programme, playback.active.getCurrentPosition())
        .apply();
    lastSaved = SystemClock.elapsedRealtime();
  }

  @Override
  protected void onPause() {
    super.onPause();
    savePosition();
    if (playback != null) {
      playback.cancel();
      if (playback.active != null) playback.active.pause();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    getWindow().getDecorView().setSystemUiVisibility(5894);
    if (playback != null && playback.active != null) playback.active.play();
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    ui.removeCallbacksAndMessages(null);
    io.shutdownNow();
    if (playback != null) playback.close();
    super.onDestroy();
  }
}
