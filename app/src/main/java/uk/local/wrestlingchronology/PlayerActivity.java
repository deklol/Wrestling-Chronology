package uk.local.wrestlingchronology;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
  EpisodePage episodePage;
  CommunityViews communityViews;
  CommunityPanel discussion;
  ExoPlayer player;
  DefaultTrackSelector trackSelector;
  volatile int selectedQuality, qualityCeiling = 720;
  boolean hidingPlayerControls;
  float playbackVolume = 1f, unmutedVolume = 1f;
  ImageButton volumeButton;
  SeekBar volumeSlider;
  PlayerView view;
  MediaSession session;
  TextView heading, status, aspectButton;
  LinearLayout top;
  SharedPreferences playbackPreferences;
  int index, optionIndex, generation, failedEpisodes;
  boolean active, ended, clipping, verificationSession, controlsVisible, hasPlayed;
  boolean stretchPicture;
  float videoAspectRatio;
  long bufferingSince;
  long lastHlsRecovery;
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
    if (!verificationSession) {
      communityViews = new CommunityViews(this);
      communityViews.onViews = count -> { if (discussion != null && discussion.current()) discussion.showViews(count); };
    }
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    setContentView(root);
    view = new PlayerView(this);
    // One timer owns hiding, so losing a child control never removes the player
    // itself from the remote's focus path.
    view.setControllerShowTimeoutMs(0);
    view.setId(View.generateViewId());
    view.setFocusable(true);
    view.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    view.setOnFocusChangeListener((focusedView, focused) -> {
      if (focused && !hidingPlayerControls) focusPlayerControls();
    });
    view.setControllerHideDuringAds(true);
    view.setControllerAutoShow(false);
    view.setKeepScreenOn(true);
    view.setShowNextButton(false);
    view.setShowPreviousButton(false);
    root.addView(view, new FrameLayout.LayoutParams(-1, -1));
    playbackPreferences = getSharedPreferences("application", MODE_PRIVATE);
    stretchPicture = playbackPreferences.getBoolean("stretch_picture", false);
    ViewGroup contentFrame = view.findViewById(androidx.media3.ui.R.id.exo_content_frame);
    // Let only the video surface widen beyond its fitted 4:3 frame. The player
    // bounds still clip the unused side bars; controls and subtitles stay normal.
    contentFrame.setClipChildren(false);
    contentFrame.setClipToPadding(false);
    view.setClipChildren(true);
    View videoSurface = view.getVideoSurfaceView();
    if (videoSurface != null)
      videoSurface.addOnLayoutChangeListener(
          (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
              applyPictureMode());
    view.addOnLayoutChangeListener(
        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> applyPictureMode());
    top = Ui.vertical(this);
    top.setPadding(Ui.dp(this, 30), Ui.dp(this, 20), Ui.dp(this, 30), Ui.dp(this, 30));
    top.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xe6000000, 0x00000000}));
    root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    heading = Ui.text(this, "", 22, Ui.WHITE);
    top.addView(heading);
    Ui.gap(top, 7);
    status = Ui.text(this, "", 12, Ui.MUTED);
    top.addView(status);
    // Keep picture controls in Media3's transport bar beside its settings cog.
    view.setFullscreenButtonClickListener(fullscreen -> {
      if (episodePage != null) episodePage.setFullscreen(fullscreen);
    });
    View settingsControl = view.findViewById(androidx.media3.ui.R.id.exo_settings);
    settingsControl.setOnClickListener(v -> playerSettings());
    ViewGroup bottomControls = (ViewGroup) settingsControl.getParent();
    LinearLayout volumeControls = Ui.horizontal(this);
    volumeButton = new ImageButton(this);
    volumeButton.setImageResource(R.drawable.volume_on);
    volumeButton.setBackground(Ui.focus(this, false));
    volumeButton.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
    volumeButton.setContentDescription("Mute");
    volumeControls.addView(volumeButton, Ui.lp(this, 40, 40));
    volumeSlider = new SeekBar(this);
    volumeSlider.setMax(100); volumeSlider.setProgress(100);
    volumeSlider.setContentDescription("Playback volume: 100 percent");
    volumeSlider.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.accent()));
    volumeSlider.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.WHITE));
    volumeControls.addView(volumeSlider, Ui.lp(this, 86, 40));
    volumeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
        playbackVolume = progress / 100f;
        if (playbackVolume > 0) unmutedVolume = playbackVolume;
        if (player != null) player.setVolume(playbackVolume);
        volumeButton.setImageResource(progress == 0 ? R.drawable.volume_off : R.drawable.volume_on);
        volumeButton.setContentDescription(progress == 0 ? "Unmute" : "Mute");
        volumeSlider.setContentDescription("Playback volume: " + progress + " percent");
        if (fromUser) scheduleHide();
      }
      public void onStartTrackingTouch(SeekBar bar) { handler.removeCallbacks(hideControls); }
      public void onStopTrackingTouch(SeekBar bar) { scheduleHide(); }
    });
    volumeButton.setOnClickListener(v -> { volumeSlider.setProgress(playbackVolume > 0 ? 0 : Math.round(unmutedVolume * 100)); scheduleHide(); });
    bottomControls.addView(volumeControls, bottomControls.indexOfChild(settingsControl));
    aspectButton = Ui.text(this, "4:3", 12, Ui.WHITE);
    aspectButton.setGravity(Gravity.CENTER);
    aspectButton.setTypeface(null, android.graphics.Typeface.BOLD);
    aspectButton.setBackground(Ui.focus(this, false));
    aspectButton.setFocusable(true);
    aspectButton.setClickable(true);
    aspectButton.setOnClickListener(v -> {
      stretchPicture = !stretchPicture;
      playbackPreferences.edit().putBoolean("stretch_picture", stretchPicture).apply();
      applyPictureMode();
      scheduleHide();
    });
    bottomControls.addView(aspectButton, bottomControls.indexOfChild(settingsControl),
        new ViewGroup.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 40)));
    View fullscreenControl = view.findViewById(androidx.media3.ui.R.id.exo_fullscreen);
    fullscreenControl.setBackground(Ui.focus(this, false));
    for (int id : new int[] {androidx.media3.ui.R.id.exo_play_pause,
        androidx.media3.ui.R.id.exo_rew_with_amount, androidx.media3.ui.R.id.exo_ffwd_with_amount,
        androidx.media3.ui.R.id.exo_settings, androidx.media3.ui.R.id.exo_fullscreen})
      stylePlayerButton(view.findViewById(id));
    stylePlayerButton(volumeButton);
    stylePlayerButton(aspectButton);
    volumeControls.setGravity(Gravity.CENTER_VERTICAL);
    volumeSlider.getLayoutParams().height = Ui.dp(this, 44);
    applyPictureMode();
    view.setControllerVisibilityListener(
        (PlayerView.ControllerVisibilityListener)
            visibility -> {
              controlsVisible = visibility == View.VISIBLE;
              top.setVisibility(visibility);
              if (controlsVisible) scheduleHide();
            });
    episodePage = new EpisodePage(this, root);
    setContentView(episodePage.root);
    heading.setVisibility(View.GONE);
    status.addTextChangedListener(new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      public void onTextChanged(CharSequence s, int start, int before, int count) { episodePage.sourceInfo.setText(s); }
      public void afterTextChanged(android.text.Editable s) {}
    });
    if (verificationSession) episodePage.setFullscreen(true);
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
    selectedQuality = 0;
    if (communityViews != null) communityViews.playing(false);
    generation++;
    ended = false;
    optionIndex = 0;
    resolved = null;
    hasPlayed = false;
    bufferingSince = 0;
    videoAspectRatio = 0;
    if (player != null) {
      player.release();
      player = null;
      view.setPlayer(null);
    }
    Catalog.Episode item = current();
    if (communityViews != null) communityViews.open(Company.current.id, item.id);
    if (episodePage != null) episodePage.episode(item);
    resumePosition = Catalog.position(this, item);
    heading.setText(item.heading() + "  ·  " + Catalog.dateLabel(item.date));
    status.setText("Finding a stream…");
    top.setVisibility(View.VISIBLE);
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
                    : option.optString("type").equals("okru") ? "OK.ru" : "Internet Archive")
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

  void playerSettings() {
    if (player == null) return;
    new AlertDialog.Builder(this).setTitle("Player settings")
        .setItems(new String[] {"Quality", "Playback speed", "Audio track"}, (dialog, which) -> {
          if (which == 0) qualityMenu();
          else if (which == 1) {
            float[] speeds = {.5f, .75f, 1f, 1.25f, 1.5f, 2f};
            new AlertDialog.Builder(this).setTitle("Playback speed")
                .setItems(new String[] {"0.5×", "0.75×", "Normal", "1.25×", "1.5×", "2×"},
                    (d, index) -> { if (player != null) player.setPlaybackSpeed(speeds[index]); }).show();
          } else audioMenu();
        }).show();
  }

  void audioMenu() {
    if (player == null) return;
    ArrayList<TrackSelectionOverride> tracks = new ArrayList<>();
    ArrayList<String> labels = new ArrayList<>();
    for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
      if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
      for (int i = 0; i < group.length; i++) if (group.isTrackSupported(i)) {
        Format format = group.getTrackFormat(i);
        tracks.add(new TrackSelectionOverride(group.getMediaTrackGroup(), i));
        labels.add(format.label != null ? format.label : format.language != null ? format.language : "Original audio");
      }
    }
    new AlertDialog.Builder(this).setTitle("Audio track")
        .setItems(labels.toArray(new String[0]), (d, index) -> {
          if (trackSelector != null) trackSelector.setParameters(trackSelector.buildUponParameters().setOverrideForType(tracks.get(index)));
        }).setNegativeButton("Close", null).show();
  }

  void qualityMenu() {
    if (player == null || resolved == null) return;
    TreeSet<Integer> heights = new TreeSet<>(Collections.reverseOrder());
    heights.addAll(resolved.qualityUrls.keySet());
    for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
      if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
      for (int i = 0; i < group.length; i++)
        if (group.isTrackSupported(i) && group.getTrackFormat(i).height > 0)
          heights.add(group.getTrackFormat(i).height);
    }
    int current = player.getVideoFormat() == null ? 0 : player.getVideoFormat().height;
    ArrayList<Integer> choices = new ArrayList<>(); choices.add(0);
    ArrayList<String> labels = new ArrayList<>();
    labels.add("Auto" + (current > 0 ? " · " + current + "p" : ""));
    for (int height : heights) { choices.add(height); labels.add(height + "p" + (heights.size() == 1 ? " · Original" : "")); }
    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Quality")
        .setSingleChoiceItems(labels.toArray(new String[0]), Math.max(0, choices.indexOf(selectedQuality)), (d, index) -> {
          d.dismiss();
          if (player == null) return;
          selectedQuality = choices.get(index);
          updateQualityInfo();
          Log.i(TAG, "QUALITY_REQUEST provider=" + resolved.provider + " height=" + selectedQuality
              + " pos=" + player.getCurrentPosition() + " buffered=" + player.getTotalBufferedDuration());
          if (!resolved.qualityUrls.isEmpty()) {
            int limit = selectedQuality == 0 ? Catalog.prefs(this).getInt("quality", 720) : selectedQuality;
            int height = resolved.qualityUrls.firstKey();
            for (int available : resolved.qualityUrls.keySet()) if (available <= limit) height = available;
            changeProgressiveQuality(height);
          } else {
            qualityCeiling = Catalog.prefs(this).getInt("quality", 720);
            Toast.makeText(this, selectedQuality == 0 ? "Automatic quality enabled" : "Switching to " + selectedQuality + "p", Toast.LENGTH_SHORT).show();
          }
        }).setNegativeButton("Close", null).create();
    dialog.show();
  }

  void stylePlayerButton(View button) {
    if (button == null) return;
    ViewGroup.LayoutParams params = button.getLayoutParams();
    params.width = params.height = Ui.dp(this, 44);
    if (params instanceof ViewGroup.MarginLayoutParams) {
      ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
      margins.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
    }
    button.setLayoutParams(params);
    button.setBackground(Ui.focus(this, false));
    button.setFocusable(true);
    if (button instanceof ImageButton) {
      int padding = Ui.dp(this, 10);
      button.setPadding(padding, padding, padding, padding);
      ((ImageButton) button).setScaleType(ImageView.ScaleType.FIT_CENTER);
    }
  }

  void updateQualityInfo() {
    if (episodePage == null || player == null) return;
    Format format = player.getVideoFormat();
    int height = format == null ? 0 : format.height;
    String text = height > 0 ? (selectedQuality == 0 ? "Auto · " : "") + height + "p" : "Loading…";
    if (selectedQuality > 0 && height > 0 && height != selectedQuality) text += " → " + selectedQuality + "p";
    episodePage.qualityInfo.setText("Quality: " + text);
  }

  void changeProgressiveQuality(int height) {
    if (player == null || resolved == null || height == resolved.selectedHeight || !resolved.qualityUrls.containsKey(height)) return;
    boolean playing = player.getPlayWhenReady();
    float speed = player.getPlaybackParameters().speed;
    save(false);
    resumePosition = player.getCurrentPosition();
    resolved.videos.set(0, resolved.qualityUrls.get(height));
    resolved.selectedHeight = height;
    prepare(resolved, ++generation);
    player.setPlayWhenReady(playing);
    player.setPlaybackSpeed(speed);
  }

  void prepare(Resolver.Resolved stream, int token) {
    archiveRecovery.reset();
    if (player != null) {
      player.release();
      player = null;
    }
    resolved = stream;
    qualityCeiling = Catalog.prefs(this).getInt("quality", 720);
    DefaultTrackSelector selector = new DefaultTrackSelector(this,
        new BufferedQualitySelection(() -> selectedQuality, () -> qualityCeiling));
    trackSelector = selector;
    selector.setParameters(
        selector
            .buildUponParameters()
            .setMaxVideoSize(1920, 1080)
            .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264)
            .setPreferredAudioLanguage("en"));
    DefaultLoadControl load =
        new DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                stream.provider.equals("okru") ? 60000 : 15000,
                stream.provider.equals("okru") ? 120000 : 45000,
                stream.provider.equals("okru") ? 2500 : 1800,
                stream.provider.equals("archive") || stream.provider.equals("okru") ? 2000 : 5000)
            .setTargetBufferBytes((stream.provider.equals("okru") ? 48 : 16) * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build();
    ExoPlayer.Builder builder = new ExoPlayer.Builder(this).setTrackSelector(selector).setLoadControl(load);
    if (stream.provider.equals("okru")) builder.setBandwidthMeter(
        new androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(this)
            .setInitialBitrateEstimate(stream.initialBitrate).build());
    player = builder.build();
    player.setVolume(playbackVolume);
    player.setAudioAttributes(
        new androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build(),
        true);
    view.setPlayer(player);
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
    if (stream.provider.equals("okru")) {
      Map<String, String> headers = new HashMap<>();
      headers.put("Referer", "https://ok.ru/");
      headers.put("User-Agent", Resolver.USER_AGENT);
      http.setDefaultRequestProperties(headers);
    }
    DataSource.Factory data = youtube ? () -> new YoutubeDataSource(http.createDataSource()) : http;
    if (stream.provider.equals("dailymotion")) data = StreamingNetwork.dailymotion(this);
    if (stream.provider.equals("okru")) data = new ResolvingDataSource.Factory(
        StreamingNetwork.okru(this), spec -> {
          if (stream.originHost != null && stream.originHost.equals(spec.uri.getHost()))
            return spec.withUri(spec.uri.buildUpon().authority(stream.deliveryHost).build());
          return spec;
        });
    DefaultMediaSourceFactory factory =
        new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, data));
    ArrayList<MediaSource> media = new ArrayList<>();
    for (String url : stream.videos) {
      MediaItem.Builder item = new MediaItem.Builder().setUri(url).setMediaId(current().id);
      if (stream.provider.equals("dailymotion") || (stream.provider.equals("okru") && stream.hls)) item.setMimeType(MimeTypes.APPLICATION_M3U8);
      else if (stream.provider.equals("okru")) item.setMimeType(MimeTypes.VIDEO_MP4);
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
          public void onVideoSizeChanged(VideoSize size) {
            if (token != generation) return;
            videoAspectRatio =
                size.height > 0 ? size.width * size.pixelWidthHeightRatio / size.height : 0;
            view.post(() -> applyPictureMode());
            Log.i(TAG, "VIDEO_QUALITY provider=" + stream.provider + " height=" + size.height);
            updateQualityInfo();
          }

          @Override
          public void onPlaybackStateChanged(int state) {
            if (token != generation) return;
            if (state == Player.STATE_READY) {
              status.setText(stream.label + "  ·  " + (index + 1) + " / " + queue.size());
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
              if (stream.hls || stream.provider.equals("dailymotion")) handler.postDelayed(() -> {
                if (token != generation || player == null || player.getPlaybackState() != Player.STATE_BUFFERING
                    || !hasPlayed || !player.getPlayWhenReady() || SystemClock.elapsedRealtime() - bufferingSince < 5500) return;
                // Allow a restarted connection to fill instead of cancelling it again six seconds later.
                if (SystemClock.elapsedRealtime() - lastHlsRecovery < 15000) return;
                lastHlsRecovery = SystemClock.elapsedRealtime();
                selectedQuality = 0;
                qualityCeiling = 360;
                updateQualityInfo();
                long position = player.getCurrentPosition();
                int part = player.getCurrentMediaItemIndex();
                Log.i(TAG, "HLS_RECOVER provider=" + stream.provider + " pos=" + position);
                // Restart a stuck request at the same time; changing a ceiling alone cannot unblock it.
                player.stop();
                player.seekTo(part, position);
                player.prepare();
                Toast.makeText(PlayerActivity.this, "Switched to Auto to keep playback moving", Toast.LENGTH_SHORT).show();
                handler.postDelayed(() -> {
                  if (token == generation && player != null && selectedQuality == 0)
                    qualityCeiling = Catalog.prefs(PlayerActivity.this).getInt("quality", 720);
                }, 20000);
              }, 6000);
              if (selectedQuality == 0 && !stream.qualityUrls.isEmpty()) handler.postDelayed(() -> {
                if (token != generation || player == null || player.getPlaybackState() != Player.STATE_BUFFERING) return;
                int lower = 0;
                for (int height : stream.qualityUrls.keySet()) if (height < stream.selectedHeight) lower = height;
                if (lower > 0) changeProgressiveQuality(lower);
              }, 6000);
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
            if (token != generation) return;
            if (communityViews != null) communityViews.playing(playing && active && player.getPlaybackState() == Player.STATE_READY);
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
    top.setVisibility(View.VISIBLE);
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
            .setBufferedPosition(player.getBufferedPosition())
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
          boolean hadPlayerFocus = view.hasFocus();
          hidingPlayerControls = true;
          androidx.media3.ui.PlayerControlView controller = view.findViewById(androidx.media3.ui.R.id.exo_controller);
          controller.hideImmediately();
          if (hadPlayerFocus) view.requestFocus();
          hidingPlayerControls = false;
          top.setVisibility(View.GONE);
          controlsVisible = false;
        }
      };

  void scheduleHide() {
    handler.removeCallbacks(hideControls);
    if (player != null && player.isPlaying()) handler.postDelayed(hideControls, 3500);
  }

  void focusPlayerControls() {
    view.showController();
    View play = view.findViewById(androidx.media3.ui.R.id.exo_play_pause);
    if (play != null && play.isShown()) play.requestFocus();
    scheduleHide();
  }

  void applyPictureMode() {
    if (aspectButton != null) {
      aspectButton.setText(stretchPicture ? "16:9" : "4:3");
      aspectButton.setContentDescription(
          stretchPicture
              ? "Picture stretched horizontally. Select for original proportions."
              : "Original proportions. Select to stretch the 4:3 picture to full width.");
    }
    View surface = view.getVideoSurfaceView();
    if (surface == null || surface.getWidth() == 0 || view.getWidth() == 0) return;
    float scale = 1f;
    if (stretchPicture && videoAspectRatio > 0) {
      // A 4:3 broadcast may be encoded inside a wider frame with black side
      // bars. Widen its central picture, without increasing its height.
      float pictureFraction = Math.min(1f, (4f / 3f) / videoAspectRatio);
      float pictureWidth = surface.getWidth() * pictureFraction;
      scale = Math.max(1f, view.getWidth() / pictureWidth);
    }
    surface.setPivotX(surface.getWidth() / 2f);
    surface.setScaleX(scale);
    surface.setScaleY(1f);
  }

  @Override
  public void onBackPressed() {
    if (episodePage != null && episodePage.fullscreen) {
      episodePage.setFullscreen(false);
      return;
    }
    if (episodePage != null) { finish(); return; }
    if (controlsVisible) {
      view.hideController();
      top.setVisibility(View.GONE);
      controlsVisible = false;
      handler.removeCallbacks(hideControls);
    } else super.onBackPressed();
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    int key = event.getKeyCode();
    boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
    if (key == KeyEvent.KEYCODE_MENU && episodePage != null) {
      if (down && event.getRepeatCount() == 0) episodePage.discussion();
      return true;
    }
    if (discussion != null && discussion.navigate(event)) return true;
    boolean direction = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
        || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
    if (down && direction && view != null && view.hasFocus() && !controlsVisible) {
      focusPlayerControls();
      return true;
    }
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
    if (communityViews != null) communityViews.playing(false);
    // MainActivity resumes before this player's onStop: persist before its hero is rebuilt.
    save(false);
    super.onPause();
  }

  @Override
  protected void onStop() {
    save(false);
    active = false;
    if (communityViews != null) communityViews.playing(false);
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
    if (discussion != null) discussion.close();
    if (communityViews != null) communityViews.dispose();
    work.shutdownNow();
    if (session != null) session.release();
    super.onDestroy();
  }
}
