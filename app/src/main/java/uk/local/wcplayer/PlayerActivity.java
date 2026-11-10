package uk.local.wcplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlayerActivity extends Activity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private JSONObject entry;
  private ExoPlayer player;
  private PlayerView view;
  private TextView status;
  private Resolver.Resolved resolved;
  private int quality = 720;
  private int generation;
  private boolean destroyed, stretched;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    Ui.allowLandscapeRotation(this);
    entry = Library.find(this, getIntent().getStringExtra("id"));
    if (entry == null) { finish(); return; }
    LinearLayout root = Ui.vertical(this); root.setBackgroundColor(Ui.BG);
    LinearLayout top = Ui.horizontal(this);
    int pad = Ui.dp(this, 12); top.setPadding(pad, pad, pad, pad);
    top.addView(Ui.button(this, "Back", false, this::finish)); Ui.gap(top, 16);
    TextView title = Ui.text(this, entry.optString("title", "Video"), 20, Ui.WHITE);
    title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    top.addView(Ui.button(this, "Quality", false, this::chooseQuality)); Ui.gap(top, 10);
    TextView aspect = Ui.button(this, "Fit", false, () -> {});
    aspect.setOnClickListener(button -> {
      stretched = !stretched;
      view.setResizeMode(stretched ? AspectRatioFrameLayout.RESIZE_MODE_FILL : AspectRatioFrameLayout.RESIZE_MODE_FIT);
      aspect.setText(stretched ? "Stretch" : "Fit");
    });
    top.addView(aspect); root.addView(top);
    view = new PlayerView(this); view.setFocusable(true); view.setKeepScreenOn(true);
    root.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
    status = Ui.text(this, "Opening video...", 14, Ui.MUTED); status.setPadding(pad, pad, pad, pad);
    root.addView(status); setContentView(root);
    open(entry.optLong("position", 0));
  }

  private void open(long position) {
    int token = ++generation;
    status.setText("Opening video...");
    worker.execute(() -> {
      try {
        Resolver.Resolved result = Resolver.resolve(entry.optString("url"), quality);
        handler.post(() -> {
          if (destroyed || isFinishing() || token != generation) return;
          resolved = result; prepare(position);
        });
      } catch (Exception error) {
        handler.post(() -> {
          if (!destroyed && token == generation) status.setText("Could not open this video. Check the file or its original source.");
        });
      }
    });
  }

  private void prepare(long position) {
    if (player != null) { view.setPlayer(null); player.release(); }
    DefaultTrackSelector tracks = new DefaultTrackSelector(this);
    tracks.setParameters(tracks.buildUponParameters().setMaxVideoSize(1920, quality));
    player = new ExoPlayer.Builder(this).setTrackSelector(tracks)
        .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(30000, 120000, 1500, 3000)
            .setTargetBufferBytes(16 * 1024 * 1024).build()).build();
    DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
        .setUserAgent(Resolver.USER_AGENT).setConnectTimeoutMs(12000).setReadTimeoutMs(18000);
    if ("okru".equals(resolved.provider)) http.setDefaultRequestProperties(Collections.singletonMap("Referer", "https://ok.ru/"));
    DataSource.Factory network = "youtube".equals(resolved.provider)
        ? () -> new YoutubeDataSource(http.createDataSource()) : http;
    DefaultMediaSourceFactory sources = new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, network));
    ArrayList<MediaSource> items = new ArrayList<>();
    for (String url : resolved.videos) {
      MediaItem.Builder item = new MediaItem.Builder().setUri(url);
      if (resolved.hls) item.setMimeType(MimeTypes.APPLICATION_M3U8);
      else if (resolved.dash) item.setMimeType(MimeTypes.APPLICATION_MPD);
      items.add(sources.createMediaSource(item.build()));
    }
    if (resolved.audio != null && items.size() == 1) {
      items.set(0, new MergingMediaSource(items.get(0), sources.createMediaSource(MediaItem.fromUri(resolved.audio))));
    }
    player.setMediaSources(items); view.setPlayer(player); player.seekTo(Math.max(0, position));
    player.addListener(new Player.Listener() {
      @Override public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_READY) status.setText(resolved.label == null ? "Video" : resolved.label);
        else if (state == Player.STATE_BUFFERING) status.setText("Loading video...");
      }
      @Override public void onPlayerError(PlaybackException error) {
        if (resolved.dash && resolved.fallbackHls != null) {
          long at = player.getCurrentPosition();
          resolved.videos.clear(); resolved.videos.add(resolved.fallbackHls);
          resolved.dash = false; resolved.hls = true; resolved.fallbackHls = null; prepare(at);
        } else status.setText("This file or source could not play. It may be unavailable or use an unsupported format.");
      }
    });
    player.prepare(); player.play(); view.requestFocus(); view.showController();
  }

  private void chooseQuality() {
    String[] labels = {"360p", "480p", "720p", "1080p"};
    int[] heights = {360, 480, 720, 1080};
    new AlertDialog.Builder(this).setTitle("Maximum video quality").setItems(labels, (dialog, which) -> {
      quality = heights[which];
      long position = player == null ? entry.optLong("position", 0) : player.getCurrentPosition();
      open(position);
    }).show();
  }

  @Override protected void onPause() {
    super.onPause();
    if (player == null) return;
    player.pause();
    ArrayList<JSONObject> rows = Library.read(this);
    for (JSONObject row : rows) if (row.optString("id").equals(entry.optString("id"))) {
      try { row.put("position", player.getCurrentPosition()); } catch (Exception ignored) { }
    }
    Library.save(this, rows);
  }

  @Override protected void onDestroy() {
    destroyed = true; generation++; worker.shutdownNow(); handler.removeCallbacksAndMessages(null);
    if (player != null) { view.setPlayer(null); player.release(); player = null; }
    super.onDestroy();
  }
}
