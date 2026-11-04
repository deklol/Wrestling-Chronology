package uk.local.wrestlingchronology;

import android.content.Context;
import android.os.*;
import android.util.Log;
import android.view.TextureView;
import android.widget.FrameLayout;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.*;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** One tuning pipeline for guide selection, channel keys and programme navigation. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class TvPlayback {
  interface Listener {
    void notice(String message);

    void playing(TvGuide.Slot slot);

    void failed(TvGuide.Slot slot, String message);

    void ended(TvGuide.Slot slot);
  }

  final Context context;
  final TvApi api;
  final FrameLayout surface;
  final Listener listener;
  final CommunityViews views;
  final TvPlaybackFaults faults;
  final Handler main = new Handler(Looper.getMainLooper());
  final ExecutorService io = Executors.newFixedThreadPool(2);
  ExoPlayer active, pending;
  TextureView activeView, pendingView;
  TvGuide.Slot playing, requested;
  Future<?> resolution;
  int generation, quality;
  boolean stretched, muted, closed;
  long tuneStarted;
  private long startupDeadline;
  private Runnable timeout, slowNotice, retryTask;
  private final TvRetry retry = new TvRetry();
  private boolean joinLive, automaticTransition;
  private int videoWidth = 16, videoHeight = 9;

  TvPlayback(Context context, TvApi api, FrameLayout surface, Listener listener) {
    this.context = context;
    this.api = api;
    this.surface = surface;
    this.listener = listener;
    faults = new TvPlaybackFaults(context);
    boolean diagnostic = BuildConfig.DEBUG && context instanceof android.app.Activity
        && ((android.app.Activity) context).getIntent().getBooleanExtra("test_diagnostics", false);
    views = diagnostic ? null : new CommunityViews(context);
  }

  void tune(TvGuide.Slot slot, long offset) {
    tune(slot, offset, false);
  }

  void tune(TvGuide.Slot slot, long offset, boolean live) {
    tune(slot, offset, live, false);
  }

  void tune(TvGuide.Slot slot, long offset, boolean live, boolean automatic) {
    automaticTransition = automatic;
    retry.reset();
    joinLive = live;
    startupDeadline = slot.channel.startupTimeoutMs > 0
        ? SystemClock.elapsedRealtime() + slot.channel.startupTimeoutMs : 0;
    if (active != null && active.getPlayerError() == null && active.getMediaItemCount() == 1 && playing != null
        && playing.programme.equals(slot.programme)) {
      cancel();
      requested = null;
      playing = slot;
      automaticTransition = false;
      if (!live || Math.abs(active.getCurrentPosition() - offset) > 15000)
        active.seekTo(offset);
      active.play();
      listener.notice("");
      listener.playing(slot);
      Log.i("ChronologyTV", "REUSE channel=" + slot.channel.id);
      return;
    }
    begin(slot, offset, false);
  }

  private void begin(TvGuide.Slot slot, long offset, boolean retrying) {
    if (closed) return;
    cancel();
    final int token = generation;
    requested = slot;
    long remaining = startupDeadline > 0 ? startupDeadline - SystemClock.elapsedRealtime() : 30000;
    if (remaining <= 0) { fail(token, slot, "TV channel unavailable"); return; }
    tuneStarted = SystemClock.elapsedRealtime();
    listener.notice(retrying ? (automaticTransition ? "Retrying next show..." : "Retrying channel, please wait...")
        : automaticTransition ? "" : "Changing channel, please wait...");
    Log.i("ChronologyTV", "TUNE generation=" + token + " channel=" + slot.channel.id);
    slowNotice =
        () -> {
          if (token == generation) listener.notice(automaticTransition ? "Loading next show..."
              : "Still loading channel...\nLarger or higher-quality recordings can take a little longer to load.");
        };
    timeout = () -> {
      if (startupDeadline > 0) fail(token, slot, "TV channel unavailable");
      else recover(token, slot, offset, new java.net.SocketTimeoutException("Channel startup timed out"));
    };
    main.postDelayed(slowNotice, automaticTransition ? 12000 : 5000);
    main.postDelayed(timeout, remaining);
    resolution =
        io.submit(
            () -> {
              try {
                if (faults.take("resolve", slot.channel.id))
                  throw new java.net.SocketTimeoutException("Controlled resolver timeout");
                JSONObject stream;
                do {
                  if (Thread.currentThread().isInterrupted()) return;
                  stream =
                      api.request("/api/play", new JSONObject().put("programme", slot.programme)
                          .put("transport", "native"));
                  if ("failed".equals(stream.optString("state")))
                    throw new java.io.IOException("TV channel unavailable");
                  if (!"ready".equals(stream.optString("state"))) Thread.sleep(650);
                } while (!"ready".equals(stream.optString("state")));
                Resolver.Resolved deviceStream = null;
                if (stream.has("source")) {
                  try {
                    deviceStream = Resolver.resolveTv(stream.getJSONObject("source"),
                        Catalog.prefs(context).getInt("quality", 720));
                  } catch (Exception error) {
                    // Older provider extractors can stop working independently
                    // of this app. Use the server resolver only for that source.
                    if (Thread.currentThread().isInterrupted()) return;
                    Log.w("ChronologyTV", "DEVICE_RESOLVE_FAILED provider=" + stream.optString("provider"));
                    stream = api.request("/api/play", new JSONObject().put("programme", slot.programme));
                  }
                }
                final JSONObject resolved = stream;
                final Resolver.Resolved direct = deviceStream;
                main.post(
                    () -> {
                  if (token == generation && !closed) prepare(token, slot, offset, resolved, direct);
                    });
              } catch (Exception error) {
                if (!Thread.currentThread().isInterrupted()) {
                  Log.w("ChronologyTV", "RESOLVE_FAILED channel=" + slot.channel.id
                      + " error=" + error.getClass().getSimpleName());
                  main.post(() -> recover(token, slot, offset, error));
                }
              }
            });
  }

  private void prepare(int token, TvGuide.Slot slot, long offset, JSONObject stream,
                       Resolver.Resolved direct) {
    try {
      boolean okru = "okru".equals(stream.optString("provider"));
      boolean buffered = okru || "archive".equals(stream.optString("provider"));
      Runtime runtime = Runtime.getRuntime();
      long used = runtime.totalMemory() - runtime.freeMemory();
      JSONObject index = stream.optJSONObject("archiveIndex");
      boolean progressive = direct != null ? !direct.hls && !direct.dash
          : "archive".equals(stream.optString("provider"));
      if (active != null && TvMemory.needsSinglePlayer(runtime.maxMemory(), used,
          index == null ? 0 : index.optLong("headerLength"), slot.end-slot.start, progressive)) {
        retireActive();
        Log.i("ChronologyTV", "SINGLE_PLAYER channel=" + slot.channel.id);
      }
      if (BuildConfig.DEBUG) Log.i("ChronologyTV", "PREPARE channel=" + slot.channel.id
          + " provider=" + stream.optString("provider") + " direct=" + (direct != null)
          + " dash=" + (direct != null && direct.dash) + " offset=" + offset);
      DefaultTrackSelector selector = new DefaultTrackSelector(context,
          new BufferedQualitySelection(() -> 0,
              () -> Catalog.prefs(context).getInt("quality", 720)));
      selector.setParameters(
          selector
              .buildUponParameters()
              .setPreferredAudioLanguage("en")
              .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264)
              .setMaxVideoSize(1920, 1080));
      ExoPlayer next =
          new ExoPlayer.Builder(context)
              .setTrackSelector(selector)
              .setLoadControl(
                  new DefaultLoadControl.Builder()
                      .setBufferDurationsMs(buffered ? 60000 : 15000, buffered ? 120000 : 90000,
                          buffered ? 6000 : 1200, buffered ? 6000 : 2500)
                      .setTargetBufferBytes((buffered ? 48 : 24) * 1024 * 1024)
                      .setPrioritizeTimeOverSizeThresholds(false)
                      .build())
              .setBandwidthMeter(
                  new androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(context)
                      .setInitialBitrateEstimate(okru && direct != null
                          ? direct.initialBitrate : 8000000)
                      .build())
              .build();
      pending = next;
      TextureView texture = new TextureView(context);
      pendingView = texture;
      // Keep the outgoing picture above the staging surface until a decoded frame arrives.
      surface.addView(texture, 0, new FrameLayout.LayoutParams(-1, -1));
      next.setVideoTextureView(texture);
      // Configure the sink before decoding. Changing its content type at the
      // handover flushes already-buffered audio and can cause an audible hiccup.
      next.setAudioAttributes(
          new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
              .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false);
      next.setVolume(0);
      DefaultHttpDataSource.Factory http =
          new DefaultHttpDataSource.Factory()
              .setUserAgent(Resolver.USER_AGENT)
              .setConnectTimeoutMs(7000)
              .setReadTimeoutMs(8000);
      DataSource.Factory delivery = http;
      if (direct != null) {
        if ("youtube".equals(direct.provider)) {
          Map<String, String> headers = new HashMap<>();
          headers.put("Referer", "https://www.youtube.com/");
          headers.put("Origin", "https://www.youtube.com");
          http.setDefaultRequestProperties(headers);
          delivery = () -> new YoutubeDataSource(http.createDataSource());
        } else if ("dailymotion".equals(direct.provider)) {
          delivery = StreamingNetwork.dailymotion(context);
        } else if ("okru".equals(direct.provider)) {
          http.setDefaultRequestProperties(Collections.singletonMap("Referer", "https://ok.ru/"));
          delivery = new ResolvingDataSource.Factory(StreamingNetwork.okru(context), spec -> {
            if (direct.originHost != null && direct.originHost.equals(spec.uri.getHost()))
              return spec.withUri(spec.uri.buildUpon().authority(direct.deliveryHost).build());
            return spec;
          });
        }
      }
      final DataSource.Factory fallbackDelivery = delivery;
      delivery = StreamingNetwork.bufferedMedia(direct, delivery, http, () -> {});
      DataSource.Factory authenticated =
          new ResolvingDataSource.Factory(
              delivery,
              spec -> {
                if (spec.uri.toString().startsWith(BuildConfig.COMMUNITY_BASE_URL + "/tv/"))
                  return spec.withAdditionalHeaders(
                      Collections.singletonMap("Authorization", "Bearer " + api.credential));
                return spec;
              });
      DataSource.Factory media = authenticated;
      if (direct != null && "archive".equals(direct.provider) && stream.has("archiveIndex")) {
        JSONObject metadata=stream.getJSONObject("archiveIndex");
        ArchiveLayout layout=new ArchiveLayout(metadata);
        String header=api.mediaUrl(metadata.getString("header"));
        media=() -> new ArchiveIndexDataSource(authenticated.createDataSource(),layout,header);
        if (BuildConfig.DEBUG) Log.i("ChronologyTV", "ARCHIVE_INDEX channel=" + slot.channel.id
            + " headerBytes=" + layout.headerLength + " payload=direct");
      }
      DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(media);
      if (faults.take("media", slot.channel.id))
        factory = new DefaultMediaSourceFactory(TvPlaybackFaults.failedMedia());
      if (faults.take("deadline", slot.channel.id)) {
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, 250);
      }
      JSONArray parts;
      if (direct == null) parts = stream.getJSONArray("parts");
      else {
        parts = new JSONArray();
        for (String url : direct.videos)
          parts.put(new JSONObject().put("url", url).put("type",
              direct.dash ? "dash" : direct.hls || "dailymotion".equals(direct.provider) ? "hls" : "mp4"));
      }
      ArrayList<MediaSource> sources = new ArrayList<>();
      int partIndex = 0;
      long position = offset;
      for (int i = 0; i < parts.length(); i++) {
        JSONObject part = parts.getJSONObject(i);
        MediaItem item =
            new MediaItem.Builder()
                .setUri(api.mediaUrl(part.getString("url")))
                .setMimeType(
                    "dash".equals(part.optString("type")) ? MimeTypes.APPLICATION_MPD : "hls".equals(part.optString("type"))
                        ? MimeTypes.APPLICATION_M3U8
                        : MimeTypes.VIDEO_MP4)
                .build();
        MediaSource source = factory.createMediaSource(item);
        if (direct != null && direct.audio != null)
          source = new MergingMediaSource(source,
              factory.createMediaSource(MediaItem.fromUri(direct.audio)));
        sources.add(source);
        long duration = (long) (part.optDouble("duration", 0) * 1000);
        if (duration > 0 && position >= duration && i < parts.length() - 1) {
          position -= duration;
          partIndex++;
        }
      }
      if (BuildConfig.DEBUG) next.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
        @Override public void onDroppedVideoFrames(EventTime eventTime, int dropped, long elapsed) {
          Log.i("ChronologyTV", "DROPPED channel=" + slot.channel.id + " count=" + dropped + " elapsedMs=" + elapsed);
        }
        @Override public void onAudioUnderrun(EventTime eventTime, int bufferSize, long bufferSizeMs, long sinceFeed) {
          Log.i("ChronologyTV", "AUDIO_UNDERRUN channel=" + slot.channel.id + " bufferMs=" + bufferSizeMs + " sinceFeedMs=" + sinceFeed);
        }
      });
      next.addListener(
          new Player.Listener() {
            boolean firstFrame;
            int width = 16, height = 9;

            @Override
            public void onVideoSizeChanged(VideoSize size) {
              width = Math.round(size.width * size.pixelWidthHeightRatio);
              height = size.height;
              fit(texture, width, height);
              if (next == active) {
                videoWidth = width;
                videoHeight = height;
                quality = size.height;
              }
            }

            @Override
            public void onRenderedFirstFrame() {
              firstFrame = true;
              commit();
            }

            @Override
            public void onIsPlayingChanged(boolean value) {
              if (value) commit();
              if (next == active && views != null) views.playing(value);
            }

            void commit() {
              if (!firstFrame || !next.isPlaying() || next != pending || token != generation)
                return;
              if (timeout != null) main.removeCallbacks(timeout);
              if (slowNotice != null) main.removeCallbacks(slowNotice);
              ExoPlayer previous = active;
              TextureView old = activeView;
              active = next;
              activeView = texture;
              pending = null;
              pendingView = null;
              playing = slot;
              automaticTransition = false;
              requested = null;
              retry.playing(SystemClock.elapsedRealtime());
              videoWidth = width;
              videoHeight = height;
              quality = height;
              if (previous != null) {
                previous.setVolume(0);
                previous.release();
              }
              if (old != null) surface.removeView(old);
              next.setAudioAttributes(
                  new AudioAttributes.Builder()
                      .setUsage(C.USAGE_MEDIA)
                      .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                      .build(),
                  true);
              next.setVolume(muted ? 0 : 1);
              fit(texture, width, height);
              listener.notice("");
              if (views != null) {
              views.playing(false);
              views.open("", "");
              // Films and TV-only extras have no company episode counter.
              for (Company company : Company.ALL) {
                if (company.id.equals(slot.company) && !slot.episode.isEmpty()) {
                  views.open(slot.company, slot.episode);
                  views.playing(next.isPlaying());
                  break;
                }
              }
              }
              listener.playing(slot);
              Log.i(
                  "ChronologyTV",
                  "PLAYING channel="
                      + slot.channel.id
                      + " elapsedMs="
                      + (SystemClock.elapsedRealtime() - tuneStarted)
                      + " height="
                      + height);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
              int currentToken = next == active && requested == null ? generation : token;
              if (direct != null && direct.dash && direct.fallbackHls != null
                  && currentToken == generation && (next == pending || next == active)) {
                // A recording may expose a DASH URL its CDN no longer serves.
                // Try its published HLS alternative once, without dropping the
                // outgoing channel or recreating the player and audio surface.
                String hls = direct.fallbackHls;
                direct.fallbackHls = null;
                direct.dash = false;
                direct.hls = true;
                direct.videos.set(0, hls);
                long resume = next.getCurrentPosition();
                if (next == pending) firstFrame = false;
                MediaItem fallback = new MediaItem.Builder().setUri(hls)
                    .setMimeType(MimeTypes.APPLICATION_M3U8).build();
                next.setMediaSource(new DefaultMediaSourceFactory(fallbackDelivery)
                    .createMediaSource(fallback), resume);
                next.prepare();
                next.play();
                Log.w("ChronologyTV", "DASH_FALLBACK channel=" + slot.channel.id);
                return;
              }
              Log.e(
                  "ChronologyTV",
                  "PLAYBACK_ERROR channel=" + slot.channel.id + " code=" + error.getErrorCodeName(),
                  error);
              if (next == pending || (next == active && requested == null))
                recover(currentToken, slot, next.getCurrentPosition(), error);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
              if (BuildConfig.DEBUG) Log.i("ChronologyTV", "STATE channel=" + slot.channel.id
                  + " state=" + state + " position=" + next.getCurrentPosition()
                  + " buffered=" + next.getTotalBufferedDuration());
              if (next == active && requested == null && state == Player.STATE_ENDED) listener.ended(playing);
            }
          });
      next.setMediaSources(sources, partIndex, position);
      next.prepare();
      next.play();
    } catch (Exception error) {
      Log.e("ChronologyTV", "PREPARE_ERROR", error);
      recover(token, slot, offset, error);
    }
  }

  private static boolean temporary(Throwable error) {
    if (TvMemory.exhausted(error)) return false;
    // Renew expired URLs and retry network failures, not unsupported codecs or
    // a definite missing recording. No credentials or signed URLs go in logs.
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof TvApi.ResponseException)
        return TvRetry.retryHttp(((TvApi.ResponseException) cause).status);
      if (cause instanceof HttpDataSource.InvalidResponseCodeException)
        return TvRetry.retryHttp(((HttpDataSource.InvalidResponseCodeException) cause).responseCode);
      if (cause instanceof androidx.media3.common.ParserException
          || cause instanceof java.io.FileNotFoundException) return false;
    }
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.io.IOException) return true;
    }
    return false;
  }

  private void recover(int token, TvGuide.Slot slot, long offset, Throwable error) {
    if (closed || token != generation) return;
    // A later playback failure gets a fresh recovery window, not an expired tune deadline.
    if (requested == null && slot.channel.startupTimeoutMs > 0)
      startupDeadline = SystemClock.elapsedRealtime() + slot.channel.startupTimeoutMs;
    if (TvMemory.exhausted(error) && active != null) {
      // An unindexed source may exceed the estimate. Release the other decoder
      // before one fresh attempt; retrying with both players repeats the OOM.
      retireActive();
      recover(token, slot, offset, new java.io.IOException("Retry with one player"));
      return;
    }
    boolean transientFailure = temporary(error);
    long delay = transientFailure ? retry.nextDelay(SystemClock.elapsedRealtime()) : -1;
    if (startupDeadline > 0 && SystemClock.elapsedRealtime() + Math.max(0, delay) >= startupDeadline) {
      fail(token, slot, "TV channel unavailable");
      return;
    }
    if (delay < 0) {
      fail(token, slot, transientFailure ? "Could not connect to this channel" : "TV channel unavailable");
      return;
    }
    cancel();
    final int scheduled = generation;
    requested = slot;
    listener.notice(automaticTransition ? "Retrying next show..." : "Retrying channel, please wait...");
    Log.w("ChronologyTV", "RETRY channel=" + slot.channel.id + " delayMs=" + delay
        + " error=" + error.getClass().getSimpleName());
    retryTask = () -> {
      if (closed || scheduled != generation) return;
      TvGuide.Slot current = joinLive ? slot.channel.at(api.now()) : slot;
      if (current == null) { fail(scheduled, slot, "The guide needs refreshing"); return; }
      begin(current, joinLive ? current.offset(api.now()) : offset, true);
    };
    main.postDelayed(retryTask, delay);
  }

  void fail(int token, TvGuide.Slot slot, String message) {
    if (closed || token != generation) return;
    cancel();
    requested = null;
    listener.failed(slot, message);
    Log.w("ChronologyTV", "UNAVAILABLE channel=" + slot.channel.id);
  }

  void cancel() {
    generation++;
    if (resolution != null) {
      resolution.cancel(true);
      resolution = null;
    }
    if (timeout != null) main.removeCallbacks(timeout);
    if (slowNotice != null) main.removeCallbacks(slowNotice);
    if (retryTask != null) { main.removeCallbacks(retryTask); retryTask = null; }
    if (pending != null) {
      pending.release();
      pending = null;
    }
    if (pendingView != null) {
      surface.removeView(pendingView);
      pendingView = null;
    }
  }

  private void retireActive() {
    if (active == null) return;
    ExoPlayer previous = active;
    active = null;
    if (views != null) views.playing(false);
    previous.release();
    // Keep the outgoing TextureView until the incoming frame replaces it.
  }

  void fit(TextureView view, int width, int height) {
    if (view == null || width < 1 || height < 1) return;
    view.post(
        () -> {
          if (!view.isAttachedToWindow()) return;
          int w = surface.getWidth(), h = surface.getHeight();
          if (!stretched) {
            float scale = Math.min(w / (float) width, h / (float) height);
            w = Math.round(width * scale);
            h = Math.round(height * scale);
          }
          FrameLayout.LayoutParams bounds = (FrameLayout.LayoutParams) view.getLayoutParams();
          if (w > 0 && h > 0 && (bounds.width != w || bounds.height != h
              || bounds.gravity != android.view.Gravity.CENTER)) {
            bounds.width = w;
            bounds.height = h;
            bounds.gravity = android.view.Gravity.CENTER;
            view.setLayoutParams(bounds);
          }
        });
  }

  void resize() {
    fit(activeView, videoWidth, videoHeight);
  }

  void stretch(boolean value) {
    stretched = value;
    resize();
  }

  void pause() {
    joinLive = false;
    if (active != null) active.setPlayWhenReady(!active.getPlayWhenReady());
  }

  void seek(long delta) {
    joinLive = false;
    if (active != null) active.seekTo(Math.max(0, active.getCurrentPosition() + delta));
  }

  void close() {
    closed = true;
    if (views != null) views.dispose();
    cancel();
    main.removeCallbacksAndMessages(null);
    io.shutdownNow();
    if (active != null) {
      active.release();
      active = null;
    }
    surface.removeAllViews();
  }
}
