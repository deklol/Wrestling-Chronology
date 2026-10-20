package uk.local.wrestlingchronology;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

final class Ui {
  static final int BG = Color.rgb(10, 12, 18),
      PANEL = Color.rgb(23, 27, 38),
      WHITE = Color.rgb(245, 246, 250),
      MUTED = Color.rgb(160, 170, 188),
      RED = Color.rgb(227, 53, 74);

  static int dp(Context context, float value) {
    return Math.round(context.getResources().getDisplayMetrics().density * value);
  }

  static TextView text(Context context, String value, float size, int color) {
    TextView result = new TextView(context);
    result.setText(value);
    result.setTextSize(size);
    result.setTextColor(color);
    result.setIncludeFontPadding(false);
    return result;
  }

  static GradientDrawable shape(Context context, int color, int stroke) {
    GradientDrawable result = new GradientDrawable();
    result.setColor(color);
    result.setCornerRadius(dp(context, 12));
    if (stroke != 0) result.setStroke(dp(context, 2), stroke);
    return result;
  }

  static StateListDrawable focus(Context context, boolean primary) {
    return focus(context, primary, Company.current);
  }

  static int accent() {
    return Company.current.accent();
  }

  static GradientDrawable gradient(Context context, int start, int end, int stroke) {
    GradientDrawable result =
        new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {start, end});
    result.setCornerRadius(dp(context, 12));
    if (stroke != 0) result.setStroke(dp(context, 2), stroke);
    return result;
  }

  static StateListDrawable focus(Context context, boolean primary, Company company) {
    StateListDrawable state = new StateListDrawable();
    state.addState(
        new int[] {android.R.attr.state_focused},
        gradient(context, company.primary(), company.secondary(), WHITE));
    state.addState(
        new int[] {android.R.attr.state_pressed},
        gradient(context, company.primary(), company.secondary(), company.accent()));
    state.addState(
        new int[] {},
        primary
            ? gradient(context, company.primary(), company.secondary(), 0)
            : gradient(context, company.surface(), company.background(), 0));
    return state;
  }

  static TextView button(Context context, String label, boolean primary, Runnable action) {
    TextView result = text(context, label, 14, WHITE);
    result.setTypeface(null, Typeface.BOLD);
    result.setGravity(Gravity.CENTER);
    result.setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12));
    result.setBackground(focus(context, primary));
    result.setFocusable(true);
    result.setClickable(true);
    result.setOnClickListener(v -> action.run());
    return result;
  }

  static LinearLayout vertical(Context context) {
    LinearLayout result = new LinearLayout(context);
    result.setOrientation(LinearLayout.VERTICAL);
    return result;
  }

  static LinearLayout horizontal(Context context) {
    LinearLayout result = new LinearLayout(context);
    result.setOrientation(LinearLayout.HORIZONTAL);
    result.setGravity(Gravity.CENTER_VERTICAL);
    return result;
  }

  static LinearLayout.LayoutParams lp(Context context, int width, int height) {
    return new LinearLayout.LayoutParams(
        width < 0 ? width : dp(context, width), height < 0 ? height : dp(context, height));
  }

  static void gap(LinearLayout row, int value) {
    View gap = new View(row.getContext());
    row.addView(gap, lp(row.getContext(), value, value));
  }

  static void weight(LinearLayout row) {
    row.addView(new View(row.getContext()), new LinearLayout.LayoutParams(0, 1, 1));
  }
}
