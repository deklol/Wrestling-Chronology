package uk.local.wrestlingchronology;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.net.SocketTimeoutException;

/** Opt-in device tests exercise real player failures without changing any server. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class TvPlaybackFaults {
  private int remaining;
  private String kind = "", channel = "";

  TvPlaybackFaults(Context context) {
    if (!BuildConfig.DEBUG || !(context instanceof Activity)) return;
    android.content.Intent intent = ((Activity) context).getIntent();
    remaining = Math.max(0, Math.min(3, intent.getIntExtra("test_failures", 0)));
    kind = intent.getStringExtra("test_failure_kind");
    channel = intent.getStringExtra("test_failure_channel");
  }

  synchronized boolean take(String type, String id) {
    if (remaining <= 0 || !type.equals(kind) || (channel != null && !channel.equals(id))) return false;
    remaining--;
    android.util.Log.i("ChronologyTV", "INJECT_FAILURE channel=" + id + " stage=" + type);
    return true;
  }

  static DataSource.Factory failedMedia() {
    return () -> new BaseDataSource(true) {
      public long open(DataSpec spec) throws SocketTimeoutException { throw new SocketTimeoutException("Controlled media timeout"); }
      public int read(byte[] bytes, int offset, int length) { return -1; }
      public Uri getUri() { return Uri.EMPTY; }
      public void close() {}
    };
  }
}
