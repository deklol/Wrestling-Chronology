package uk.local.wrestlingchronology;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Path;
import android.util.AttributeSet;
import androidx.media3.ui.DefaultTimeBar;

/** A Media3 time bar whose track has rounded ends. Used from exo_player_control_view.xml. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class RoundedTimeBar extends DefaultTimeBar {
  final Path clip = new Path();
  final int barHeight, scrubberPadding;

  @SuppressLint("CustomViewStyleable") // Reads DefaultTimeBar's own attributes.
  public RoundedTimeBar(Context context, AttributeSet attrs) {
    super(context, attrs);
    // Mirror DefaultTimeBar's own sizing so the track can be located; its rects are private.
    TypedArray a =
        context.obtainStyledAttributes(attrs, androidx.media3.ui.R.styleable.DefaultTimeBar);
    try {
      barHeight =
          a.getDimensionPixelSize(
              androidx.media3.ui.R.styleable.DefaultTimeBar_bar_height,
              Ui.dp(context, DEFAULT_BAR_HEIGHT_DP));
      int enabled =
          a.getDimensionPixelSize(
              androidx.media3.ui.R.styleable.DefaultTimeBar_scrubber_enabled_size,
              Ui.dp(context, DEFAULT_SCRUBBER_ENABLED_SIZE_DP));
      int disabled =
          a.getDimensionPixelSize(
              androidx.media3.ui.R.styleable.DefaultTimeBar_scrubber_disabled_size,
              Ui.dp(context, DEFAULT_SCRUBBER_DISABLED_SIZE_DP));
      int dragged =
          a.getDimensionPixelSize(
              androidx.media3.ui.R.styleable.DefaultTimeBar_scrubber_dragged_size,
              Ui.dp(context, DEFAULT_SCRUBBER_DRAGGED_SIZE_DP));
      scrubberPadding = (Math.max(disabled, Math.max(enabled, dragged)) + 1) / 2;
    } finally {
      a.recycle();
    }
  }

  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    super.onLayout(changed, l, t, r, b);
    int width = r - l, height = b - t;
    // The layout uses bar_gravity="center": the track is vertically centred, inset by the
    // scrubber padding. Clip away only the track's square corners so the scrubber still shows.
    float left = getPaddingLeft() + scrubberPadding;
    float right = width - getPaddingRight() - scrubberPadding;
    float top = (height - barHeight) / 2;
    float bottom = top + barHeight;
    float radius = barHeight / 2f;
    clip.reset();
    clip.addRect(0, 0, width, top, Path.Direction.CW);
    clip.addRect(0, bottom, width, height, Path.Direction.CW);
    clip.addRect(0, top, left, bottom, Path.Direction.CW);
    clip.addRect(right, top, width, bottom, Path.Direction.CW);
    clip.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW);
  }

  @Override
  public void onDraw(Canvas canvas) {
    canvas.save();
    canvas.clipPath(clip);
    super.onDraw(canvas);
    canvas.restore();
  }
}
