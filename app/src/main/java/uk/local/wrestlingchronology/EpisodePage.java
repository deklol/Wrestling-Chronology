package uk.local.wrestlingchronology;

import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.JSONObject;

/** Website watch layout with the existing native player, not a second player or WebView. */
final class EpisodePage {
  final PlayerActivity activity;
  final FrameLayout root, video;
  final LinearLayout page, main, details, discussionHost;
  final ScrollView scroll;
  final TextView sourceInfo;
  boolean fullscreen;
  String shownEpisode = "";
  final int videoHeight;

  EpisodePage(PlayerActivity activity, FrameLayout video) {
    this.activity = activity; this.video = video;
    if (video.getParent() != null) ((ViewGroup) video.getParent()).removeView(video);
    root = new FrameLayout(activity); root.setBackgroundColor(Company.current.background());
    page = Ui.vertical(activity); page.setPadding(dp(30), dp(16), dp(30), dp(20)); root.addView(page, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout nav = Ui.horizontal(activity); page.addView(nav, Ui.lp(activity, -1, 48));
    ImageView logo = new ImageView(activity); Company.current.setLogo(logo); logo.setScaleType(ImageView.ScaleType.FIT_CENTER); nav.addView(logo, Ui.lp(activity, 70, 42));
    Ui.gap(nav, 14); nav.addView(Ui.button(activity, "Back to library", false, activity::finish)); Ui.weight(nav);
    Ui.gap(page, 16); scroll = new ScrollView(activity); scroll.setFillViewport(true); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout columns = Ui.horizontal(activity); columns.setGravity(Gravity.TOP); scroll.addView(columns);
    main = Ui.vertical(activity); columns.addView(main, new LinearLayout.LayoutParams(0, -2, 1));
    Ui.gap(columns, 22); details = Ui.vertical(activity);
    details.setPadding(dp(16), dp(18), dp(16), dp(18));
    details.setBackground(Ui.shape(activity, Company.current.surface(), 0));
    columns.addView(details, Ui.lp(activity, 260, -2));
    int screenDp = (int) (activity.getResources().getDisplayMetrics().widthPixels / activity.getResources().getDisplayMetrics().density);
    videoHeight = dp(Math.max(220, (screenDp - 60 - 282) * 9 / 16));
    video.setBackground(Ui.shape(activity, android.graphics.Color.BLACK, 0)); video.setClipToOutline(true);
    main.addView(video, new LinearLayout.LayoutParams(-1, videoHeight));
    Ui.gap(main, 18); discussionHost = Ui.vertical(activity); main.addView(discussionHost);
    Ui.gap(main, 16); main.addView(Ui.text(activity, "Your position is saved automatically. Full shows play in chronological order.", 11, Ui.MUTED));
    sourceInfo = Ui.text(activity, "Source: finding a stream…", 12, Ui.accent());
    sourceInfo.setPadding(dp(12), dp(10), dp(12), dp(10)); sourceInfo.setBackground(Ui.shape(activity, Company.current.surface(), 0));
  }
  int dp(float n) { return Ui.dp(activity, n); }
  void setFullscreen(boolean value) {
    if (fullscreen == value) return;
    fullscreen = value;
    ((ViewGroup) video.getParent()).removeView(video);
    page.setVisibility(value ? View.GONE : View.VISIBLE);
    if (value) root.addView(video, new FrameLayout.LayoutParams(-1, -1));
    else main.addView(video, 0, new LinearLayout.LayoutParams(-1, videoHeight));
    activity.heading.setVisibility(value ? View.VISIBLE : View.GONE);
    activity.view.setFullscreenButtonState(value);
    activity.view.showController(); activity.applyPictureMode();
    if (!value) scroll.scrollTo(0, 0);
    activity.view.post(() -> {
      View control = activity.view.findViewById(androidx.media3.ui.R.id.exo_fullscreen);
      if (control != null) control.requestFocus();
    });
  }
  void discussion() {
    if (fullscreen) setFullscreen(false);
    discussionHost.post(() -> { scroll.smoothScrollTo(0, discussionHost.getTop()); if (activity.discussion != null) activity.discussion.like.requestFocus(); });
  }
  void episode(Catalog.Episode item) {
    if (!shownEpisode.equals(item.id)) {
      shownEpisode = item.id;
      if (activity.discussion != null) activity.discussion.close();
      discussionHost.removeAllViews(); activity.discussion = CommunityPanel.embed(activity, discussionHost, Company.current.id, item);
      final String companyId = Company.current.id;
      activity.discussion.onSummary = data -> { if (activity.communityViews != null) activity.communityViews.acceptSummary(companyId, item.id, data); };
      scroll.scrollTo(0, 0);
    }
    details.removeAllViews();
    TextView company = Ui.text(activity, Company.current.name.toUpperCase(Locale.ROOT), 11, Ui.accent()); company.setLetterSpacing(.16f); details.addView(company);
    Ui.gap(details, 10);
    TextView title = Ui.text(activity, item.heading(), 28, Ui.WHITE); title.setTypeface(null, Typeface.BOLD); details.addView(title);
    Ui.gap(details, 10); details.addView(Ui.text(activity, Catalog.dateLabel(item.date) + (item.duration > 0 ? " · " + item.duration / 60 + " min" : ""), 13, Ui.MUTED));
    Ui.gap(details, 12);
    if (sourceInfo.getParent() != null) ((ViewGroup) sourceInfo.getParent()).removeView(sourceInfo);
    details.addView(sourceInfo); sourceInfo.setText("Source: finding a stream…");
    Ui.gap(details, 12); details.addView(Ui.text(activity, item.description.isEmpty() ? item.title : item.description, 13, Ui.MUTED));
    Ui.gap(details, 16);
    LinearLayout navigation = Ui.horizontal(activity); details.addView(navigation);
    TextView previous = Ui.button(activity, "← Previous", false, () -> {
      if (activity.index > 0) { activity.save(false); activity.index--; activity.override = null; activity.open(); }
    }); previous.setEnabled(activity.index > 0); previous.setAlpha(previous.isEnabled() ? 1f : .4f); previous.setTextSize(12); previous.setPadding(dp(6), dp(12), dp(6), dp(12)); navigation.addView(previous, new LinearLayout.LayoutParams(0, -2, 1));
    Ui.gap(navigation, 6);
    TextView next = Ui.button(activity, "Next show →", true, () -> activity.advance(false)); next.setEnabled(activity.index + 1 < activity.queue.size()); next.setAlpha(next.isEnabled() ? 1f : .4f); next.setTextSize(12); next.setPadding(dp(6), dp(12), dp(6), dp(12)); navigation.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
    if (activity.index + 1 < activity.queue.size()) {
      Catalog.Episode following = activity.queue.get(activity.index + 1); Ui.gap(details, 16);
      details.addView(Ui.text(activity, "UP NEXT", 11, Ui.MUTED)); Ui.gap(details, 5);
      details.addView(Ui.text(activity, following.heading(), 16, Ui.WHITE)); details.addView(Ui.text(activity, Catalog.dateLabel(following.date), 12, Ui.MUTED));
    }
    Ui.gap(details, 18); details.addView(Ui.text(activity, "PLAYBACK SOURCE", 11, Ui.MUTED)); Ui.gap(details, 8);
    details.addView(Ui.button(activity, "Automatic · best available", activity.override == null, () -> source(null)));
    LinkedHashSet<String> providers = new LinkedHashSet<>();
    for (JSONObject option : item.sources) providers.add(option.optString("type"));
    for (String provider : providers) {
      Ui.gap(details, 6);
      String label = provider.equals("archive") ? "Internet Archive" : provider.equals("youtube") ? "YouTube" : provider.equals("dailymotion") ? "Dailymotion" : provider;
      details.addView(Ui.button(activity, label, provider.equals(activity.override), () -> source(provider)));
    }
    Ui.gap(details, 12);
    TextView autoplay = Ui.button(activity, "", false, () -> {});
    Runnable labelAutoplay = () -> autoplay.setText("Autoplay: " + (Catalog.prefs(activity).getBoolean("autoplay", true) ? "On" : "Off")); labelAutoplay.run();
    autoplay.setOnClickListener(v -> { Catalog.prefs(activity).edit().putBoolean("autoplay", !Catalog.prefs(activity).getBoolean("autoplay", true)).apply(); labelAutoplay.run(); }); details.addView(autoplay);
    Ui.gap(details, 6);
    details.addView(Ui.button(activity, "Restart episode", false, () -> {
      if (activity.player != null) { activity.player.seekTo(0, 0); activity.player.play(); }
    }));
    Ui.gap(details, 6);
    TextView watched = Ui.button(activity, Catalog.watched(activity, item) ? "Mark unwatched" : "Mark watched", false, () -> {});
    watched.setOnClickListener(v -> { boolean value = !Catalog.watched(activity, item); Catalog.progress(activity, item, Catalog.position(activity, item), value); watched.setText(value ? "Mark unwatched" : "Mark watched"); }); details.addView(watched);
    Ui.gap(details, 12); details.addView(Ui.text(activity, activity.queue.size() + " shows in this timeline", 11, Ui.MUTED));
  }
  void source(String provider) { activity.save(false); activity.override = provider; activity.open(); }
}
