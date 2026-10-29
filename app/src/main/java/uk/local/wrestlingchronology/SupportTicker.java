package uk.local.wrestlingchronology;
import android.content.Context;
import android.graphics.*;
import android.util.TypedValue;
import android.view.View;
import java.util.Locale;
/** A clipped, continuously moving line independent of remote focus. */
final class SupportTicker extends View {
  private static final String MESSAGE = "THANK YOU FOR YOUR SUPPORT! IF YOU WOULD LIKE ACCCESS TO THE WEBSITE LIBRARY FOR $4.99 (LIFETIME ACCESS) WORKS ON ALL DEVICES WITH INTERNET, INCLUDING OFFLINE DOWNLOAD FEATURES, CONTACT ME ON X.COM (x.com/digitalm1nd) OR REDDIT (u/dekkytsh)".toUpperCase(Locale.ROOT);
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final float width, gap, speed;
  private float offset;
  private long lastFrame;
  SupportTicker(Context context) {
    super(context);
    setBackgroundColor(Color.BLACK);setFocusable(false);
    setContentDescription(MESSAGE);
    paint.setColor(Color.WHITE);paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
    paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,26,getResources().getDisplayMetrics()));
    width=paint.measureText(MESSAGE);
    gap=Ui.dp(context,128);speed=Ui.dp(context,64);
  }
  @Override protected void onAttachedToWindow(){super.onAttachedToWindow();lastFrame=0;postInvalidateOnAnimation();}
  @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);lastFrame=0;if(visibility==VISIBLE)postInvalidateOnAnimation();}
  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    long now=android.os.SystemClock.uptimeMillis();
    if(lastFrame!=0)offset=(offset+Math.min(100,now-lastFrame)*speed/1000f)%(width+gap);
    lastFrame=now;
    Paint.FontMetrics f=paint.getFontMetrics();
    float baseline=(getHeight()-f.ascent-f.descent)/2;
    float x=Ui.dp(getContext(),18)-offset;
    canvas.drawText(MESSAGE,x,baseline,paint);
    if(x+width+gap<getWidth())canvas.drawText(MESSAGE,x+width+gap,baseline,paint);
    if(isShown() && getWindowVisibility()==VISIBLE)postInvalidateOnAnimation();
  }
}
