package uk.local.wrestlingchronology;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Optional support, with the same company palette and remote navigation as the library. */
final class DonatePage {
  static final String PAYPAL = "dekky@live.co.uk";
  private final MainActivity activity;
  private final Company company;
  private final boolean columns;

  private DonatePage(MainActivity activity) {
    this.activity = activity;
    company = Company.current;
    columns = activity.getResources().getConfiguration().screenWidthDp >= 720;
  }

  static void show(MainActivity activity, LinearLayout root) {
    new DonatePage(activity).render(root);
  }

  private void render(LinearLayout root) {
    ScrollView scroll = new ScrollView(activity);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout body = Ui.vertical(activity);
    body.setPadding(dp(12), dp(16), dp(12), dp(24));
    scroll.addView(body);
    body.addView(label("SUPPORT WRESTLING CHRONOLOGY", 12, company.accent()));
    Ui.gap(body, 10);
    TextView title = label("Made by a fan. Built for fans.", 28, Ui.WHITE);
    title.setTypeface(null, Typeface.BOLD);
    body.addView(title);
    Ui.gap(body, 10);
    paragraph(body, "Never expected. Always appreciated.");

    LinearLayout story = card("About me");
    paragraph(
        story,
        "I’m a solo developer and a wrestling fan of over 30 years. I’m building the app I’ve"
            + " always wanted: somewhere fans like me can revisit old favourites, discover"
            + " something new and enjoy wrestling together.");
    paragraph(
        story,
        "I’m doing this on my own while trying to make ends meet. Your support helps me cover the"
            + " costs and keep putting time into making Wrestling Chronology better.");
    LinearLayout free = card("Android will always be free.");
    paragraph(
        free,
        "Donations are never required. You’re welcome here whether you donate or simply enjoy the"
            + " app.");
    paragraph(free, "Watching, sharing the app and being part of the community mean a lot too.");
    pair(body, story, free);

    LinearLayout paypal = card("Donate through PayPal");
    paragraph(paypal, "Donations of any amount are welcome. Send yours to:");
    TextView address = label(PAYPAL, 22, Ui.WHITE);
    address.setTypeface(null, Typeface.BOLD);
    paypal.addView(address);
    Ui.gap(paypal, 14);
    paypal.addView(
        Ui.button(
            activity,
            "Copy PayPal email",
            true,
            () -> {
              ClipboardManager clipboard =
                  (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
              clipboard.setPrimaryClip(ClipData.newPlainText("PayPal email", PAYPAL));
              Toast.makeText(activity, "PayPal email copied: " + PAYPAL, Toast.LENGTH_SHORT).show();
            }));
    Ui.gap(paypal, 8);
    paypal.addView(
        Ui.button(
            activity,
            "Open PayPal",
            false,
            () -> activity.openAboutLink("https://www.paypal.com/")));
    LinearLayout access = card("Private web app access");
    TextView offer = label("Donations over $4.99 include lifetime access.", 16, company.accent());
    offer.setTypeface(null, Typeface.BOLD);
    access.addView(offer);
    Ui.gap(access, 12);
    paragraph(
        access,
        "Enjoy extra features on phones, tablets, laptops and other devices with a web browser and"
            + " an internet connection.");
    paragraph(access, "Access is arranged personally using the options below.");
    pair(body, paypal, access);

    LinearLayout contact = card("How to get your access code");
    paragraph(contact, "After donating over $4.99, choose whichever is easiest:");
    heading(contact, "Leave your email in the donation note");
    paragraph(contact, "I’ll email your access code to that address.");
    heading(contact, "Or send me a private message");
    paragraph(contact, "DM me on Reddit or X and let me know you’ve donated.");
    contact.addView(
        Ui.button(
            activity,
            "Reddit · u/dekkytsh",
            false,
            () -> activity.openAboutLink("https://www.reddit.com/user/dekkytsh/")));
    Ui.gap(contact, 8);
    contact.addView(
        Ui.button(
            activity,
            "X · @digitalm1nd",
            false,
            () -> activity.openAboutLink("https://x.com/digitalm1nd")));
    Ui.gap(contact, 16);
    paragraph(
        contact,
        "I send codes myself, so please allow me time to reply. Thank you for helping me keep this"
            + " going.");
    body.addView(contact);
  }

  private LinearLayout card(String title) {
    LinearLayout card = Ui.vertical(activity);
    card.setPadding(dp(22), dp(20), dp(22), dp(20));
    card.setBackground(Ui.focus(activity, false, company));
    card.setFocusable(true);
    heading(card, title);
    return card;
  }

  private void pair(LinearLayout body, LinearLayout first, LinearLayout second) {
    LinearLayout row = columns ? Ui.horizontal(activity) : Ui.vertical(activity);
    row.setGravity(Gravity.TOP);
    body.addView(row, new LinearLayout.LayoutParams(-1, -2));
    row.addView(
        first,
        columns ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2));
    Ui.gap(row, 16);
    row.addView(
        second,
        columns ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2));
    Ui.gap(body, 16);
  }

  private void heading(LinearLayout parent, String value) {
    TextView text = label(value, 19, Ui.WHITE);
    text.setTypeface(null, Typeface.BOLD);
    parent.addView(text);
    Ui.gap(parent, 12);
  }

  private void paragraph(LinearLayout parent, String value) {
    TextView text = label(value, 14, Ui.MUTED);
    text.setLineSpacing(dp(3), 1);
    parent.addView(text);
    Ui.gap(parent, 12);
  }

  private TextView label(String value, int size, int color) {
    return Ui.text(activity, value, size, color);
  }

  private int dp(int value) {
    return Ui.dp(activity, value);
  }
}
