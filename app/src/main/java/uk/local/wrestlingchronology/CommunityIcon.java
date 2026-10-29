package uk.local.wrestlingchronology;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** The website's outlined thumbs-up, discussion and eye icons; no emoji substitution. */
final class CommunityIcon extends Drawable {
  final Paint paint = new Paint(3);
  final int kind;
  CommunityIcon(int kind, int color) {
    this.kind = kind; paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.7f); paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND);
  }
  @Override public void draw(Canvas canvas) {
    canvas.save(); canvas.translate(getBounds().left, getBounds().top);
    canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
    Path path = new Path();
    if (kind == 0) {
      path.addRect(3, 10, 7, 21, Path.Direction.CW);
      path.moveTo(10, 21); path.lineTo(18, 21); path.quadTo(20, 21, 20, 19.4f);
      path.lineTo(21.5f, 11.4f); path.quadTo(22, 9, 19.5f, 9); path.lineTo(14, 9);
      path.lineTo(15, 5); path.quadTo(15.5f, 2.5f, 13, 2); path.lineTo(8, 10); path.lineTo(8, 19); path.close();
    } else if (kind == 1) {
      path.moveTo(4, 3); path.lineTo(20, 3); path.lineTo(21, 4); path.lineTo(21, 16);
      path.lineTo(20, 17); path.lineTo(9, 17); path.lineTo(3, 21); path.lineTo(3, 4); path.close();
    } else {
      path.moveTo(2, 12); path.cubicTo(5.5f, 2.7f, 18.5f, 2.7f, 22, 12);
      path.cubicTo(18.5f, 21.3f, 5.5f, 21.3f, 2, 12); path.close();
      path.addCircle(12, 12, 3, Path.Direction.CW);
    }
    canvas.drawPath(path, paint); canvas.restore();
  }
  @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
  @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
  @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
