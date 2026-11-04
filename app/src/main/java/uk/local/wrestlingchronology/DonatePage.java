package uk.local.wrestlingchronology;

import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class DonatePage {
  static final String DISCORD = "https://discord.com/invite/Het5wyA5D";

  static void show(MainActivity activity, LinearLayout root) {
    ScrollView scroll = new ScrollView(activity);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout body = Ui.vertical(activity);
    int padding = Ui.dp(activity, 24);
    body.setPadding(padding, padding, padding, padding);
    scroll.addView(body);

    LinearLayout card = Ui.vertical(activity);
    card.setPadding(padding, padding, padding, padding);
    card.setBackground(Ui.shape(activity, Company.current.surface(), 16));
    body.addView(card, new LinearLayout.LayoutParams(-1, -2));

    TextView title = Ui.text(activity, "Community & support", 26, Ui.WHITE);
    title.setTypeface(null, Typeface.BOLD);
    card.addView(title);
    Ui.gap(card, 16);
    TextView message = Ui.text(activity,
        "Join our Discord community for direct update news, to share your own archive collections and feature ideas, and to find out how to enable web access.",
        16, Ui.MUTED);
    message.setLineSpacing(Ui.dp(activity, 3), 1);
    card.addView(message);
    Ui.gap(card, 12);
    TextView support = Ui.text(activity,
        "If you'd like to donate, you'll find information in the community. Support is optional; everyone is welcome to join.",
        16, Ui.MUTED);
    support.setLineSpacing(Ui.dp(activity, 3), 1);
    card.addView(support);
    Ui.gap(card, 20);
    card.addView(Ui.button(activity, "Join Discord", true, () -> activity.openAboutLink(DISCORD)));
    Ui.gap(card, 12);
    card.addView(Ui.text(activity, DISCORD, 14, Ui.MUTED));
  }
}
