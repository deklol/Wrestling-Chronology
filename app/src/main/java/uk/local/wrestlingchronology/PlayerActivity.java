package uk.local.wrestlingchronology;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaMetadata;
import android.media.session.*;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.*;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlayerActivity extends Activity {
  static volatile boolean playbackActive;
  static final String TAG = "WCWPlayer";
  final Handler handler = new Handler(Looper.getMainLooper());
  final ExecutorService work = Executors.newSingleThreadExecutor();
  final ArrayList<Catalog.Episode> queue = new ArrayList<>();
  final ArchiveRecovery archiveRecovery = new ArchiveRecovery();
  ExoPlayer player;
  PlayerView view;
  MediaSession session;
  TextView status, upNext, caption;
  int index, optionIndex, generation, failedEpisodes;
  boolean active, ended, clipping, verificationSession, controlsVisible, hasPlayed;
  long bufferingSince;
  long resumePosition;
  ArrayList<JSONObject> options;
  Resolver.Resolved resolved;
  String override;

  Catalog.Episode current() {
    return queue.get(index);
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().getDecorView().setSystemUiVisibility(5894);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (Catalog.episodes.isEmpty()) {
      Company.select(this, Company.find(getIntent().getStringExtra("company")));
      try {
        Catalog.load(this);
      } catch (Exception error) {
        finish();
        return;
      }
    }
    ArrayList<String> ids = getIntent().getStringArrayListExtra("queue");
    if (ids != null)
      for (String id : ids) {
        Catalog.Episode item = Catalog.find(id);
        if (item != null) queue.add(item);
      }
    if (queue.isEmpty()) {
      finish();
      return;
    }
    String selected = getIntent().getStringExtra("id");
    for (int i = 0; i < queue.size(); i++) if (queue.get(i).id.equals(selected)) index = i;
    if (state != null) index = Math.min(state.getInt("index", index), queue.size() - 1);
    override = getIntent().getStringExtra("provider");
    clipping = BuildConfig.DEBUG && getIntent().getBooleanExtra("verify_autoplay", false);
    verificationSession = clipping;
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    setContentView(root);
    view = new PlayerView(this);
    view.setControllerShowTimeoutMs(3500);
    view.setControllerHideDuringAds(true);
    view.setControllerAutoShow(false);
    view.setKeepScreenOn(true);
    view.setShowNextButton(false);
    view.setShowPreviousButton(false);
    View center = view.findViewById(androidx.media3.ui.R.id.exo_center_controls);
    if (center != null) {
      center.setLayoutParams(
          new FrameLayout.LayoutParams(
              -2,
              getResources()
                  .getDimensionPixelSize(androidx.media3.ui.R.dimen.exo_styled_bottom_bar_height),
              Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
    }
    View bottomBar = view.findViewById(androidx.media3.ui.R.id.exo_bottom_bar);
    if (bottomBar != null) {
      bottomBar.setBackground(null);
      bottomBar.setPadding(Ui.dp(this, 46), 0, Ui.dp(this, 46), 0);
    }

    View timeBar = view.findViewById(androidx.media3.ui.R.id.exo_progress);
    if (timeBar != null) timeBar.setPadding(Ui.dp(this, 53), 0, Ui.dp(this, 53), 0);
    view.setControllerAnimationEnabled(false);
    View scrim = view.findViewById(androidx.media3.ui.R.id.exo_controls_background);
    if (scrim != null)
      scrim.setBackground(
          new GradientDrawable(
              GradientDrawable.Orientation.TOP_BOTTOM,
              new int[] {0x00000000, 0x00000000, 0x40000000, 0xb3000000, 0xf2000000}));
    for (int id :
        new int[] {
          androidx.media3.ui.R.id.exo_play_pause,
          androidx.media3.ui.R.id.exo_rew_with_amount,
          androidx.media3.ui.R.id.exo_ffwd_with_amount,
          androidx.media3.ui.R.id.exo_settings
        }) {
      View button = view.findViewById(id);
      if (button == null) continue;
      int left = button.getPaddingLeft(), top = button.getPaddingTop();
      int right = button.getPaddingRight(), bottom = button.getPaddingBottom();
      button.setBackground(focusOnly(new InsetDrawable(disc(), Ui.dp(this, 4))));
      button.setPadding(left, top, right, bottom);
      if (button instanceof ImageView) ((ImageView) button).setImageTintList(focusInk());
      if (button instanceof TextView) {
        button.setForegroundTintList(focusInk());
        ((TextView) button).setTextColor(focusInk());
      }
    }
    View playPause = view.findViewById(androidx.media3.ui.R.id.exo_play_pause);
    if (playPause != null) {
      int inset = Ui.dp(this, 6);
      playPause.setPadding(inset, inset, inset, inset);
    }

    status = Ui.text(this, "", 15, Ui.MUTED);
    upNext = Ui.text(this, "", 15, Ui.MUTED);
    upNext.setVisibility(View.INVISIBLE);
    ViewGroup controller = view.findViewById(androidx.media3.ui.R.id.exo_controller);
    if (controller != null) {
      caption = Ui.text(this, "", 22, Ui.WHITE);
      FrameLayout.LayoutParams params =
          new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
      params.leftMargin = Ui.dp(this, 60);
      params.bottomMargin = Ui.dp(this, 96);
      controller.addView(caption, params);
      controller.addView(status, besideTitle());
      controller.addView(upNext, besideTitle());
    }
    root.addView(view, new FrameLayout.LayoutParams(-1, -1));
    if (center instanceof LinearLayout) {
      LinearLayout transport = (LinearLayout) center;
      transport.setBaselineAligned(false);
      LinearLayout.LayoutParams before = new LinearLayout.LayoutParams(-2, Ui.dp(this, 48));
      before.rightMargin = Ui.dp(this, 24);
      transport.addView(
          transportButton("Library", androidx.media3.ui.R.drawable.exo_ic_chevron_left, this::finish),
          0,
          before);
      LinearLayout.LayoutParams after = new LinearLayout.LayoutParams(-2, Ui.dp(this, 48));
      after.leftMargin = Ui.dp(this, 24);
      TextView next =
          transportButton(
              "Next episode", androidx.media3.ui.R.drawable.exo_ic_skip_next, () -> advance(false));
      next.setOnFocusChangeListener(
          (v, focused) -> {
            upNext.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
            status.setVisibility(focused ? View.INVISIBLE : View.VISIBLE);
          });
      transport.addView(next, after);
    }
    view.setControllerVisibilityListener(
        (PlayerView.ControllerVisibilityListener)
            visibility -> {
              controlsVisible = visibility == View.VISIBLE;
              if (controlsVisible) scheduleHide();
            });
    session = new MediaSession(this, "Wrestling Chronology");
    session.setFlags(
        MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
    session.setCallback(
        new MediaSession.Callback() {
          @Override
          public void onPlay() {
            if (player != null) player.play();
          }

          @Override
          public void onPause() {
            if (player != null) player.pause();
          }

          @Override
          public void onSeekTo(long position) {
            if (player != null) player.seekTo(position);
          }

          @Override
          public void onSkipToNext() {
            advance(false);
          }

          @Override
          public void onSkipToPrevious() {
            if (index > 0) {
              save(false);
              index--;
              open();
            }
          }
        });
  }

  @Override
  protected void onResume() {
    super.onResume();
    active = true;
    playbackActive = true;
    if (queue.isEmpty()) return;
    session.setActive(true);
    open();
    handler.post(progressTick);
    handler.post(recoveryTick);
  }

  void open() {
    generation++;
    ended = false;
    optionIndex = 0;
    resolved = null;
    hasPlayed = false;
    bufferingSince = 0;
    if (player != null) {
      player.release();
      player = null;
      view.setPlayer(null);
    }
    Catalog.Episode item = current();
    resumePosition = Catalog.position(this, item);
    if (caption != null) caption.setText(item.heading() + "  ·  " + Catalog.dateLabel(item.date));
    status.setText("Finding a stream…");
    view.showController();
    upNext.setText(
        index + 1 < queue.size()
            ? "Up next: "
                + queue.get(index + 1).heading()
                + " · "
                + Catalog.dateLabel(queue.get(index + 1).date)
            : "Last show in this queue");
    options =
        Resolver.options(
            item,
            Catalog.prefs(this).getInt("source", 1),
            override,
            Catalog.prefs(this).getInt("quality", 720));
    if (override == null && resumePosition > 0) {
      String choice = Catalog.prefs(this).getString("choice:" + item.id, "");
      String provider = Catalog.prefs(this).getString("provider:" + item.id, "");
      options.sort(
          Comparator.comparingInt(SourcePolicy::languagePreference)
              .thenComparingInt(
                  source ->
                      Catalog.sourceKey(source).equals(choice)
                          ? 0
                          : source.optString("type").equals(provider) ? 1 : 2));
    }
    Log.i(TAG, "OPEN id=" + item.id + " index=" + index + " options=" + options.size());
    tryOption(generation);
  }

  void tryOption(int token) {
    if (token != generation || !active) return;
    if (optionIndex >= options.size()) {
      unavailable(token);
      return;
    }
    JSONObject option = options.get(optionIndex);
    status.setText(
        "Connecting to "
            + (option.optString("type").equals("youtube")
                ? "official YouTube"
                : option.optString("type").equals("dailymotion")
                    ? "Dailymotion"
                    : "Internet Archive")
            + "…");
    work.execute(
        () -> {
          try {
            Resolver.Resolved stream =
                Resolver.resolve(option, Catalog.prefs(this).getInt("quality", 720));
            handler.post(
                () -> {
                  if (token == generation && active) prepare(stream, token);
                });
          } catch (Exception | LinkageError error) {
            Throwable cause = error;
            for (int depth = 0; cause != null && depth < 5; depth++, cause = cause.getCause()) {
              String detail =
                  String.valueOf(cause.getMessage()).replaceAll("https?://\\S+", "<url>");
              Log.w(
                  TAG,
                  "RESOLVE_FAILED provider="
                      + option.optString("type")
                      + " error="
                      + cause.getClass().getSimpleName()
                      + " detail="
                      + detail.substring(0, Math.min(300, detail.length())));
            }
            handler.post(
                () -> {
                  if (token == generation && active) {
                    optionIndex++;
                    tryOption(token);
                  }
                });
          }
        });
  }

  void prepare(Resolver.Resolved stream, int token) {
    archiveRecovery.reset();
    if (player != null) {
      player.release();
      player = null;
    }
    resolved = stream;
    DefaultTrackSelector selector = new DefaultTrackSelector(this);
    int max = Catalog.prefs(this).getInt("quality", 720);
    selector.setParameters(
        selector
            .buildUponParameters()
            .setMaxVideoSize(max == 720 ? 1280 : 1920, max)
            .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264)
            .setPreferredAudioLanguage("en"));
    DefaultLoadControl load =
        new DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000, 45000, 1800, stream.provider.equals("archive") ? 2000 : 5000)
            .setTargetBufferBytes(16 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build();
    player = new ExoPlayer.Builder(this).setTrackSelector(selector).setLoadControl(load).build();
    player.setAudioAttributes(
        new androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build(),
        true);
    view.setPlayer(player);
    view.requestFocus();
    DefaultHttpDataSource.Factory http =
        new DefaultHttpDataSource.Factory()
            .setUserAgent(Resolver.USER_AGENT)
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(stream.provider.equals("archive") ? 8000 : 15000);
    boolean youtube = stream.provider.equals("youtube");
    if (youtube) {
      Map<String, String> headers = new HashMap<>();
      headers.put("Referer", "https://www.youtube.com/");
      headers.put("Origin", "https://www.youtube.com");
      http.setDefaultRequestProperties(headers);
    }
    if (stream.provider.equals("dailymotion")) {
      Map<String, String> headers = new HashMap<>();
      headers.put("Referer", "https://www.dailymotion.com/");
      headers.put("Origin", "https://www.dailymotion.com");
      http.setDefaultRequestProperties(headers);
    }
    DataSource.Factory data = youtube ? () -> new YoutubeDataSource(http.createDataSource()) : http;
    if (stream.provider.equals("dailymotion")) data = StreamingNetwork.dailymotion(this);
    DefaultMediaSourceFactory factory =
        new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, data));
    ArrayList<MediaSource> media = new ArrayList<>();
    for (String url : stream.videos) {
      MediaItem.Builder item = new MediaItem.Builder().setUri(url).setMediaId(current().id);
      if (stream.provider.equals("dailymotion")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
      if (clipping)
        item.setClippingConfiguration(
            new MediaItem.ClippingConfiguration.Builder().setEndPositionMs(8000).build());
      MediaSource source = factory.createMediaSource(item.build());
      if (stream.audio != null)
        source =
            new MergingMediaSource(
                source, factory.createMediaSource(MediaItem.fromUri(stream.audio)));
      media.add(source);
    }
    int part = Math.min(Catalog.prefs(this).getInt("part:" + current().id, 0), media.size() - 1);
    if (!stream.provider.equals(
        Catalog.prefs(this).getString("provider:" + current().id, stream.provider))) {
      part = 0;
      resumePosition = 0;
    }
    player.setMediaSources(media, Math.max(0, part), clipping ? 0 : resumePosition);
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPlaybackStateChanged(int state) {
            if (token != generation) return;
            if (state == Player.STATE_READY) {
              status.setText(stream.label + " · " + (index + 1) + " of " + queue.size());
              failedEpisodes = 0;
              if (bufferingSince > 0)
                Log.i(
                    TAG,
                    "BUFFER_END provider="
                        + stream.provider
                        + " ms="
                        + (SystemClock.elapsedRealtime() - bufferingSince));
              bufferingSince = 0;
              updateSession();
              scheduleHide();
            }
            if (state == Player.STATE_BUFFERING) {
              status.setText("Buffering · " + stream.label);
              bufferingSince = SystemClock.elapsedRealtime();
              Log.i(
                  TAG,
                  "BUFFER_START provider="
                      + stream.provider
                      + " rebuffer="
                      + hasPlayed
                      + " pos="
                      + player.getCurrentPosition());
            }
            if (state == Player.STATE_ENDED && !ended) {
              ended = true;
              save(true);
              Log.i(TAG, "ENDED id=" + current().id);
              clipping = false;
              if (Catalog.prefs(PlayerActivity.this).getBoolean("autoplay", true))
                handler.post(() -> advance(true));
              else completed();
            }
          }

          @Override
          public void onIsPlayingChanged(boolean playing) {
            if (playing) {
              hasPlayed = true;
              scheduleHide();
            } else handler.removeCallbacks(hideControls);
            updateSession();
          }

          @Override
          public void onRenderedFirstFrame() {
            Log.i(TAG, "FIRST_FRAME id=" + current().id + " provider=" + stream.provider);
          }

          @Override
          public void onPlayerError(PlaybackException error) {
            if (token != generation) return;
            Log.w(
                TAG,
                "PLAY_FAILED id="
                    + current().id
                    + " provider="
                    + stream.provider
                    + " code="
                    + error.getErrorCodeName());
            if (player != null) {
              save(false);
              resumePosition = Math.max(0, player.getCurrentPosition());
              player.release();
              player = null;
              view.setPlayer(null);
            }
            optionIndex++;
            tryOption(token);
          }
        });
    session.setMetadata(
        new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, current().heading())
            .putString(
                MediaMetadata.METADATA_KEY_ARTIST,
                Company.current.name + " · " + Catalog.dateLabel(current().date))
            .build());
    player.prepare();
    player.play();
    view.showController();
  }

  void save(boolean complete) {
    if (queue.isEmpty() || verificationSession) return;
    if (complete) {
      Catalog.progress(this, current(), 0, true);
      Catalog.prefs(this).edit().putInt("part:" + current().id, 0).apply();
      return;
    }
    if (player != null
        && resolved != null
        && !ended
        && player.getPlaybackState() != Player.STATE_IDLE) {
      Catalog.progress(this, current(), player.getCurrentPosition(), false);
      saveDisplayProgress();
      Catalog.prefs(this)
          .edit()
          .putInt("part:" + current().id, player.getCurrentMediaItemIndex())
          .putString("provider:" + current().id, resolved.provider)
          .putString("choice:" + current().id, Catalog.sourceKey(options.get(optionIndex)))
          .apply();
    }
  }

  void saveDisplayProgress() {
    SharedPreferences prefs = Catalog.prefs(this);
    SharedPreferences.Editor edit = prefs.edit();
    int count = player.getMediaItemCount(), currentPart = player.getCurrentMediaItemIndex();
    Timeline timeline = player.getCurrentTimeline();
    Timeline.Window window = new Timeline.Window();
    long prefix = 0, total = 0;
    boolean prefixKnown = true, totalKnown = true;
    for (int i = 0; i < count; i++) {
      String key = "partLength:" + current().id + ":" + resolved.provider + ":" + i;
      long length =
          i < timeline.getWindowCount()
              ? timeline.getWindow(i, window).getDurationMs()
              : C.TIME_UNSET;
      if (length > 0) edit.putLong(key, length);
      else length = prefs.getLong(key, 0);
      if (length <= 0) {
        totalKnown = false;
        if (i < currentPart) prefixKnown = false;
      } else {
        total += length;
        if (i < currentPart) prefix += length;
      }
    }
    if (totalKnown && total > 0) edit.putLong("length:" + current().id, total);
    if (prefixKnown) edit.putLong("seen:" + current().id, prefix + player.getCurrentPosition());
    edit.apply();
  }

  void advance(boolean automatic) {
    if (!active) return;
    if (!automatic) save(false);
    if (index + 1 >= queue.size()) {
      completed();
      return;
    }
    index++;
    override = null;
    open();
  }

  void completed() {
    if (!active) return;
    new AlertDialog.Builder(this)
        .setTitle(index + 1 >= queue.size() ? "Year queue complete" : "Episode complete")
        .setMessage("Your progress is saved.")
        .setPositiveButton("Back to library", (dialog, which) -> finish())
        .show();
  }

  void unavailable(int token) {
    Log.w(TAG, "UNAVAILABLE id=" + current().id);
    status.setText("This show is currently unavailable from its listed sources.");
    view.showController();
    if (Catalog.prefs(this).getBoolean("skip", true)
        && index + 1 < queue.size()
        && failedEpisodes++ < 3) {
      status.append(" Moving to the next show…");
      handler.postDelayed(
          () -> {
            if (token == generation && active) advance(false);
          },
          3500);
    } else
      new AlertDialog.Builder(this)
          .setTitle("Couldn't play this show")
          .setMessage("Its hosts may be unavailable. Your progress has been kept.")
          .setPositiveButton("Try again", (dialog, which) -> open())
          .setNeutralButton("Next show", (dialog, which) -> advance(false))
          .setNegativeButton("Library", (dialog, which) -> finish())
          .show();
  }

  final Runnable progressTick =
      new Runnable() {
        public void run() {
          if (active) {
            save(false);
            updateSession();
            handler.postDelayed(this, 5000);
          }
        }
      };

  final Runnable recoveryTick =
      new Runnable() {
        public void run() {
          if (!active) return;
          ExoPlayer currentPlayer = player;
          if (currentPlayer != null) {
            int state = currentPlayer.getPlaybackState();
            boolean eligible =
                resolved != null
                    && resolved.provider.equals("archive")
                    && hasPlayed
                    && !ended
                    && currentPlayer.getPlayWhenReady()
                    && currentPlayer.getPlaybackSuppressionReason()
                        == Player.PLAYBACK_SUPPRESSION_REASON_NONE
                    && currentPlayer.getPlayerError() == null
                    && (state == Player.STATE_BUFFERING || state == Player.STATE_READY);
            ArchiveRecovery.Action action =
                archiveRecovery.sample(
                    SystemClock.elapsedRealtime(),
                    eligible,
                    currentPlayer.isPlaying(),
                    currentPlayer.getCurrentMediaItemIndex(),
                    currentPlayer.getCurrentPosition(),
                    currentPlayer.getBufferedPosition());
            if (action != ArchiveRecovery.Action.NONE) {
              int part = currentPlayer.getCurrentMediaItemIndex();
              long position = currentPlayer.getCurrentPosition();
              Log.i(TAG, "ARCHIVE_STALL action=" + action + " part=" + part + " pos=" + position);
              if (action == ArchiveRecovery.Action.RECONNECT) {
                status.setText("Reconnecting to Internet Archive…");
                view.showController();
                // Reopen the transport at the exact current part/time; never skip ahead.
                currentPlayer.stop();
                currentPlayer.seekTo(part, position);
                currentPlayer.prepare();
                currentPlayer.play();
              } else {
                save(false);
                resumePosition = position;
                generation++;
                player = null;
                view.setPlayer(null);
                currentPlayer.release();
                optionIndex++;
                tryOption(generation);
              }
            }
          } else archiveRecovery.reset();
          handler.postDelayed(this, 1000);
        }
      };

  void updateSession() {
    if (session == null || player == null) return;
    session.setPlaybackState(
        new PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY
                    | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SEEK_TO
                    | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(
                player.isPlaying()
                    ? PlaybackState.STATE_PLAYING
                    : player.getPlayWhenReady()
                            && player.getPlaybackState() == Player.STATE_BUFFERING
                        ? PlaybackState.STATE_BUFFERING
                        : PlaybackState.STATE_PAUSED,
                player.getCurrentPosition(),
                1f)
            .build());
  }

  final Runnable hideControls =
      () -> {
        if (active && player != null && player.isPlaying()) {
          view.hideController();
          controlsVisible = false;
        }
      };

  FrameLayout.LayoutParams besideTitle() {
    FrameLayout.LayoutParams result =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
    result.rightMargin = Ui.dp(this, 60);
    result.bottomMargin = Ui.dp(this, 99);
    return result;
  }

  static ColorStateList focusInk() {
    return new ColorStateList(
        new int[][] {{android.R.attr.state_focused}, {}}, new int[] {Color.BLACK, Color.WHITE});
  }

  static GradientDrawable disc() {
    GradientDrawable result = new GradientDrawable();
    result.setShape(GradientDrawable.OVAL);
    result.setColor(Color.WHITE);
    return result;
  }

  static StateListDrawable focusOnly(Drawable focused) {
    StateListDrawable result = new StateListDrawable();
    result.addState(new int[] {android.R.attr.state_focused}, focused);
    return result;
  }

  TextView transportButton(String label, int icon, Runnable action) {
    TextView result = Ui.text(this, label, 15, Ui.WHITE);
    result.setTypeface(null, Typeface.BOLD);
    result.setGravity(Gravity.CENTER);
    result.setTextColor(focusInk());
    Drawable glyph = getDrawable(icon).mutate();
    glyph.setTintList(focusInk());
    glyph.setBounds(0, 0, Ui.dp(this, 24), Ui.dp(this, 24));
    result.setCompoundDrawables(glyph, null, null, null);
    result.setCompoundDrawablePadding(Ui.dp(this, 8));
    GradientDrawable pill = Ui.shape(this, Ui.WHITE, 0);
    pill.setCornerRadius(Ui.dp(this, 24));
    result.setBackground(focusOnly(pill));
    result.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 18), 0);
    result.setFocusable(true);
    result.setClickable(true);
    result.setOnClickListener(v -> action.run());
    return result;
  }

  void scheduleHide() {
    handler.removeCallbacks(hideControls);
    if (player != null && player.isPlaying()) handler.postDelayed(hideControls, 3500);
  }

  @Override
  public void onBackPressed() {
    if (controlsVisible) {
      view.hideController();
      controlsVisible = false;
      handler.removeCallbacks(hideControls);
    } else super.onBackPressed();
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    int key = event.getKeyCode();
    boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
    if (key == KeyEvent.KEYCODE_BACK) {
      if (!down && event.getRepeatCount() == 0) onBackPressed();
      return true;
    }
    if (down) scheduleHide();
    if (key == KeyEvent.KEYCODE_MEDIA_NEXT) {
      if (down && event.getRepeatCount() == 0) advance(false);
      return true;
    }
    if (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || key == KeyEvent.KEYCODE_MEDIA_REWIND) {
      if (down && player != null)
        player.seekTo(
            Math.max(
                0,
                player.getCurrentPosition()
                    + (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? 1 : -1)
                        * (event.getRepeatCount() > 4 ? 120000 : 30000)));
      return true;
    }
    if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
      if (down && player != null) {
        if (player.getPlayWhenReady()) player.pause();
        else player.play();
        view.showController();
      }
      return true;
    }
    return super.dispatchKeyEvent(event);
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putInt("index", index);
  }

  @Override
  protected void onPause() {
    // MainActivity resumes before this player's onStop: persist before its hero is rebuilt.
    save(false);
    super.onPause();
  }

  @Override
  protected void onStop() {
    save(false);
    active = false;
    playbackActive = false;
    generation++;
    handler.removeCallbacksAndMessages(null);
    if (player != null) {
      view.setPlayer(null);
      player.release();
      player = null;
    }
    if (session != null) session.setActive(false);
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    work.shutdownNow();
    if (session != null) session.release();
    super.onDestroy();
  }
}
