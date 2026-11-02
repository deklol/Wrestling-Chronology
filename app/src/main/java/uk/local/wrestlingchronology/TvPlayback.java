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
  final Handler main = new Handler(Looper.getMainLooper());
  final ExecutorService io = Executors.newFixedThreadPool(2);
  ExoPlayer active, pending;
  TextureView activeView, pendingView;
  TvGuide.Slot playing, requested;
  Future<?> resolution;
  int generation, quality;
  boolean stretched, muted, closed;
  long tuneStarted;
  private Runnable timeout, slowNotice;
  private int videoWidth = 16, videoHeight = 9;

  TvPlayback(Context context, TvApi api, FrameLayout surface, Listener listener) {
    this.context = context;
    this.api = api;
    this.surface = surface;
    this.listener = listener;
    boolean diagnostic = BuildConfig.DEBUG && context instanceof android.app.Activity
        && ((android.app.Activity) context).getIntent().getBooleanExtra("test_diagnostics", false);
    views = diagnostic ? null : new CommunityViews(context);
  }

  void tune(TvGuide.Slot slot, long offset) {
    if (closed) return;
    cancel();
    final int token = generation;
    requested = slot;
    tuneStarted = SystemClock.elapsedRealtime();
    listener.notice("Changing channel, please wait...");
    Log.i("ChronologyTV", "TUNE generation=" + token + " channel=" + slot.channel.id);
    slowNotice =
        () -> {
          if (token == generation) listener.notice("Still loading channel...");
        };
    timeout = () -> fail(token, slot, "TV channel unavailable");
    main.postDelayed(slowNotice, 10000);
    main.postDelayed(timeout, 30000);
    resolution =
        io.submit(
            () -> {
              try {
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
                  main.post(() -> fail(token, slot, "TV channel unavailable"));
                }
              }
            });
  }

  private void prepare(int token, TvGuide.Slot slot, long offset, JSONObject stream,
                       Resolver.Resolved direct) {
    try {
      boolean okru = "okru".equals(stream.optString("provider"));
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
                      .setBufferDurationsMs(okru ? 60000 : 15000, okru ? 120000 : 90000,
                          okru ? 2500 : 1200, okru ? 4000 : 2500)
                      .setTargetBufferBytes((okru ? 48 : 24) * 1024 * 1024)
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
          delivery = new ResolvingDataSource.Factory(StreamingNetwork.okru(context), spec -> {
            if (direct.originHost != null && direct.originHost.equals(spec.uri.getHost()))
              return spec.withUri(spec.uri.buildUpon().authority(direct.deliveryHost).build());
            return spec;
          });
        }
      }
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
      JSONArray parts;
      if (direct == null) parts = stream.getJSONArray("parts");
      else {
        parts = new JSONArray();
        for (String url : direct.videos)
          parts.put(new JSONObject().put("url", url).put("type",
              direct.hls || "dailymotion".equals(direct.provider) ? "hls" : "mp4"));
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
                    "hls".equals(part.optString("type"))
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
              requested = null;
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
              Log.e(
                  "ChronologyTV",
                  "PLAYBACK_ERROR channel=" + slot.channel.id + " code=" + error.getErrorCodeName(),
                  error);
              if (next == pending) fail(token, slot, "TV channel unavailable");
              else if (next == active) {
                listener.failed(
                    slot, "This channel stopped responding. Choose another channel or retry.");
              }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
              if (BuildConfig.DEBUG) Log.i("ChronologyTV", "STATE channel=" + slot.channel.id
                  + " state=" + state + " position=" + next.getCurrentPosition()
                  + " buffered=" + next.getTotalBufferedDuration());
              if (next == active && state == Player.STATE_ENDED) listener.ended(slot);
            }
          });
      next.setMediaSources(sources, partIndex, position);
      next.prepare();
      next.play();
    } catch (Exception error) {
      Log.e("ChronologyTV", "PREPARE_ERROR", error);
      fail(token, slot, "TV channel unavailable");
    }
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
    if (pending != null) {
      pending.release();
      pending = null;
    }
    if (pendingView != null) {
      surface.removeView(pendingView);
      pendingView = null;
    }
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
    if (active != null) active.setPlayWhenReady(!active.getPlayWhenReady());
  }

  void seek(long delta) {
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
