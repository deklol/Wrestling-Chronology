package uk.local.wrestlingchronology;

import android.app.*;
import android.graphics.Typeface;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import java.text.DateFormat;
import java.util.*;
import org.json.*;

/** Native, remote-friendly counterpart of frontend/community.js. */
final class CommunityPanel {
  final Activity activity;
  final CommunityApi api;
  final String company, episode;
  final Dialog dialog;
  final LinearLayout body, comments;
  final TextView status, counts, like, more;
  final TextView commentCount;
  final EditText name, message;
  boolean liked, closed;
  long next;
  long views, total;
  java.util.function.Consumer<JSONObject> onSummary;
  interface Task { JSONObject run() throws Exception; }
  interface Result { void accept(JSONObject data) throws Exception; }

  static CommunityPanel embed(Activity activity, LinearLayout container, String company, Catalog.Episode item) {
    CommunityPanel panel = new CommunityPanel(activity, company, item);
    container.addView(panel.body, new LinearLayout.LayoutParams(-1, -2));
    return panel;
  }

  private CommunityPanel(Activity owner, String company, Catalog.Episode item) {
    activity = owner; this.company = company; episode = item.id; api = new CommunityApi(owner);
    dialog = null;
    body = Ui.vertical(owner);
    body.setPadding(Ui.dp(owner, 24), Ui.dp(owner, 20), Ui.dp(owner, 24), Ui.dp(owner, 24));
    body.setBackground(Ui.shape(owner, Company.current.surface(), 0x22ffffff));
    LinearLayout engagement = Ui.horizontal(owner); body.addView(engagement);
    engagement.addView(Ui.text(owner, "Episode discussion", 20, Ui.WHITE));
    Ui.gap(engagement, 12);
    counts = Ui.text(owner, "Loading…", 12, Ui.MUTED);
    counts.setGravity(Gravity.RIGHT);
    engagement.addView(counts, new LinearLayout.LayoutParams(0, -2, 1));
    Ui.gap(engagement, 10);
    like = Ui.button(owner, "Like", false, this::like); like.setEnabled(false); engagement.addView(like);
    Ui.gap(body, 12);
    body.addView(Ui.text(owner, "Comments, likes and views are shared with everyone on the website and app.", 13, Ui.MUTED));
    status = Ui.text(owner, "Loading discussion…", 13, Ui.accent()); body.addView(status);
    Ui.gap(body, 16);
    body.addView(Ui.text(owner, "Display name", 12, Ui.MUTED));
    name = field(body, "Display name (optional)", 32, false);
    name.setLayoutParams(Ui.lp(owner, 260, -2));
    name.setText(api.prefs.getString("displayName", ""));
    Ui.gap(body, 12);
    body.addView(Ui.text(owner, "Join the conversation", 12, Ui.MUTED));
    message = field(body, "What did you think of this episode?", 1000, true);
    Ui.gap(body, 12);
    LinearLayout formActions = Ui.horizontal(owner); body.addView(formActions);
    formActions.addView(Ui.text(owner, "Up to 1,000 characters · display names aren’t verified.", 11, Ui.MUTED), new LinearLayout.LayoutParams(0, -2, 1));
    TextView post = Ui.button(owner, "Post comment", true, () -> {});
    post.setOnClickListener(v -> post(0, message, post, null)); formActions.addView(post);
    Ui.gap(body, 18);
    LinearLayout commentsHeading = Ui.horizontal(owner); body.addView(commentsHeading);
    commentCount = Ui.text(owner, "Comments", 16, Ui.WHITE);
    commentsHeading.addView(commentCount, new LinearLayout.LayoutParams(0, -2, 1));
    commentsHeading.addView(Ui.text(owner, "Newest first", 11, Ui.MUTED));
    comments = Ui.vertical(owner); body.addView(comments);
    more = Ui.button(owner, "More comments", false, () -> load(true));
    more.setVisibility(View.GONE); body.addView(more);
    load(false);
  }

  // Explicit document order avoids geometric focus jumps across the watch-page columns.
  void focusOrder(View node, ArrayList<View> controls) {
    if (node.getVisibility() != View.VISIBLE || !node.isEnabled()) return;
    if (node.isFocusable()) controls.add(node);
    if (node instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) node;
      for (int i = 0; i < group.getChildCount(); i++) focusOrder(group.getChildAt(i), controls);
    }
  }

  boolean navigate(KeyEvent event) {
    View focused = activity.getCurrentFocus();
    ArrayList<View> controls = new ArrayList<>();
    focusOrder(body, controls);
    int index = controls.indexOf(focused);
    int key = event.getKeyCode();
    if (index < 0 || (key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN)) return false;
    // While the keyboard is open, let the editor and IME own cursor navigation.
    if (focused instanceof EditText && keyboardVisible()) return false;
    int next = index + (key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
    if (next < 0) return false;
    if (event.getAction() == KeyEvent.ACTION_DOWN && next < controls.size()) controls.get(next).requestFocus();
    return true;
  }

  boolean keyboardVisible() {
    android.graphics.Rect visible = new android.graphics.Rect();
    body.getWindowVisibleDisplayFrame(visible);
    return body.getRootView().getHeight() - visible.bottom > Ui.dp(activity, 120);
  }

  boolean current() { return !closed && !activity.isDestroyed() && !activity.isFinishing() && Company.current.id.equals(company); }
  void close() { closed = true; }
  void run(View button, Task task, Result done) {
    if (button != null) button.setEnabled(false);
    CommunityApi.IO.execute(() -> {
      try { JSONObject data = task.run(); activity.runOnUiThread(() -> {
        if (!current()) return;
        if (button != null) button.setEnabled(true);
        try { done.accept(data); } catch (Exception e) { status.setVisibility(View.VISIBLE); status.setText("Could not display discussion."); }
      }); } catch (Exception error) { activity.runOnUiThread(() -> {
        if (!current()) return;
        if (button != null) button.setEnabled(true);
        status.setVisibility(View.VISIBLE);
        status.setText(error instanceof java.net.SocketTimeoutException ? "Community timed out. Please try again." :
            error instanceof CommunityApi.ApiError ? error.getMessage() : "Community is temporarily unavailable. Please try again.");
      }); }
    });
  }
  void summary(JSONObject data) {
    liked = data.optBoolean("liked", liked);
    updateLike(data.optLong("likes")); like.setEnabled(true);
    total = data.optLong("total");
    commentCount.setText(String.format(Locale.getDefault(), "%,d comments", total));
    showViews(data.optLong("views"));
    name.setHint(data.optString("guestName", "Guest"));
  }
  void showViews(long value) {
    views = Math.max(views, value);
    counts.setText(String.format(Locale.getDefault(), "%,d views", views));
  }
  void updateLike(long count) {
    like.setText((liked ? "Liked  " : "Like  ") + count);
    like.setTextColor(liked ? Ui.accent() : Ui.WHITE);
    CommunityIcon icon = new CommunityIcon(0, liked ? Ui.accent() : Ui.WHITE);
    icon.setBounds(0, 0, Ui.dp(activity, 18), Ui.dp(activity, 18));
    like.setCompoundDrawables(icon, null, null, null); like.setCompoundDrawablePadding(Ui.dp(activity, 7));
  }
  void load(boolean append) {
    final boolean restoreMore = append && more.hasFocus();
    run(more, () -> api.read(company, episode, append ? "&cursor=" + next : ""), data -> {
      summary(data); status.setText(""); status.setVisibility(View.GONE);
      if (onSummary != null) onSummary.accept(data);
      if (!append) comments.removeAllViews();
      JSONArray rows = data.optJSONArray("comments");
      if (rows != null) for (int i = 0; i < rows.length(); i++) comment(comments, rows.getJSONObject(i));
      if (comments.getChildCount() == 0) comments.addView(Ui.text(activity, "Be the first to leave a comment.", 14, Ui.MUTED));
      next = data.optLong("next", 0); more.setVisibility(next > 0 ? View.VISIBLE : View.GONE);
      if (restoreMore) {
        if (next > 0) more.requestFocus();
        else message.requestFocus();
      }
    });
  }
  void like() {
    final boolean desired = !liked;
    run(like, () -> api.write(company, episode, new JSONObject().put("action", "like").put("active", desired)), data -> {
      liked = data.optBoolean("liked"); updateLike(data.optLong("likes")); status.setText("");
    });
  }
  EditText field(LinearLayout into, String hint, int limit, boolean multiline) {
    EditText field = new EditText(activity); field.setTextColor(Ui.WHITE); field.setHintTextColor(Ui.MUTED);
    field.setTextSize(16); field.setHint(hint); field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(limit)});
    field.setBackground(Ui.focus(activity, false));
    field.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 12), Ui.dp(activity, 14), Ui.dp(activity, 12));
    field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | (multiline ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
    field.setMinLines(multiline ? 3 : 1); field.setMaxLines(multiline ? 5 : 1);
    into.addView(field, new LinearLayout.LayoutParams(-1, -2)); return field;
  }
  void post(long parent, EditText input, View submit, LinearLayout composer) {
    String text = input.getText().toString().trim(), displayName = name.getText().toString().trim();
    if (text.isEmpty()) { input.setError("Write a comment first."); return; }
    api.prefs.edit().putString("displayName", displayName).apply();
    run(submit, () -> {
      JSONObject action = new JSONObject().put("action", "comment").put("name", displayName).put("body", text);
      if (parent > 0) action.put("parent", parent);
      return api.write(company, episode, action);
    }, data -> { input.setText(""); if (composer != null) composer.removeAllViews(); message.requestFocus(); load(false); });
  }
  void comment(LinearLayout into, JSONObject row) {
    LinearLayout rowLayout = Ui.horizontal(activity); rowLayout.setGravity(Gravity.TOP);
    Ui.gap(into, 16); into.addView(rowLayout);
    String displayName = row.optString("name", "Guest");
    TextView avatar = Ui.text(activity, displayName.isEmpty() ? "G" : displayName.substring(0, displayName.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT), 15, Ui.WHITE);
    avatar.setGravity(Gravity.CENTER); avatar.setBackground(Ui.gradient(activity, Company.current.primary(), Company.current.secondary(), 0));
    rowLayout.addView(avatar, Ui.lp(activity, 34, 34)); Ui.gap(rowLayout, 12);
    LinearLayout card = Ui.vertical(activity); rowLayout.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
    TextView author = Ui.text(activity, displayName + "  ·  " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(new Date(row.optLong("created") * 1000)), 14, Ui.WHITE);
    author.setTypeface(null, Typeface.BOLD); card.addView(author);
    Ui.gap(card, 8); card.addView(Ui.text(activity, row.optString("body"), 16, Ui.WHITE));
    long id = row.optLong("id"), parent = row.optLong("parent", 0);
    LinearLayout actions = Ui.horizontal(activity), composer = Ui.vertical(activity);
    Ui.gap(card, 12); card.addView(actions); card.addView(composer);
    TextView replyButton = Ui.button(activity, "Reply", false, () -> {});
    actions.addView(replyButton);
    replyButton.setOnClickListener(v -> {
      composer.removeAllViews();
      Ui.gap(composer, 16);
      composer.addView(Ui.text(activity, "Replying to " + displayName, 13, Ui.MUTED));
      Ui.gap(composer, 8);
      EditText reply = field(composer, "Write a reply…", 1000, true);
      Ui.gap(composer, 12);
      LinearLayout replyActions = Ui.horizontal(activity); replyActions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); composer.addView(replyActions);
      replyActions.addView(Ui.button(activity, "Cancel", false, () -> { composer.removeAllViews(); replyButton.requestFocus(); }));
      Ui.gap(replyActions, 10);
      TextView send = Ui.button(activity, "Post reply", true, () -> {});
      send.setOnClickListener(button -> post(parent > 0 ? parent : id, reply, send, composer)); replyActions.addView(send);
      reply.requestFocus();
    });
    if (row.optBoolean("canDelete")) {
      TextView delete = Ui.button(activity, "Delete", false, () -> {});
      delete.setOnClickListener(v -> new AlertDialog.Builder(activity).setTitle("Delete comment?")
          .setMessage("This also deletes any replies to this comment.").setNegativeButton("Cancel", null)
          .setPositiveButton("Delete", (d, w) -> run(delete,
              () -> api.write(company, episode, new JSONObject().put("action", "delete").put("commentId", id)), data -> load(false))).show());
      Ui.gap(actions, 8); actions.addView(delete);
    }
    if (parent == 0 && row.optLong("replies") > 0) {
      LinearLayout replies = Ui.vertical(activity); card.addView(replies);
      TextView expand = Ui.button(activity, "View " + row.optLong("replies") + " replies", false, () -> {});
      long[] cursor = {0};
      expand.setOnClickListener(v -> run(expand, () -> api.read(company, episode, "&parent=" + id + (cursor[0] > 0 ? "&cursor=" + cursor[0] : "")), data -> {
        JSONArray rows = data.getJSONArray("comments");
        for (int i = 0; i < rows.length(); i++) comment(replies, rows.getJSONObject(i));
        cursor[0] = data.optLong("next", 0);
        expand.setText("More replies"); expand.setVisibility(cursor[0] > 0 ? View.VISIBLE : View.GONE);
        ArrayList<View> replyControls = new ArrayList<>();
        focusOrder(replies, replyControls);
        if (!replyControls.isEmpty()) replyControls.get(0).requestFocus();
      })); card.addView(expand);
    }
  }

  static LinearLayout badges(android.content.Context context, JSONObject stats) {
    LinearLayout row = Ui.horizontal(context);
    row.setPadding(Ui.dp(context, 7), Ui.dp(context, 5), Ui.dp(context, 7), Ui.dp(context, 5));
    row.setBackground(Ui.shape(context, 0xe8070a10, 0));
    String[] keys = {"likes", "comments", "views"};
    for (int i = 0; i < keys.length; i++) {
      if (i > 0) Ui.gap(row, 9);
      long count = stats.optLong(keys[i]);
      String label = count >= 1000000 ? String.format(Locale.getDefault(), "%.1fM", count / 1000000d) : count >= 1000 ? String.format(Locale.getDefault(), "%.1fK", count / 1000d) : Long.toString(count);
      TextView value = Ui.text(context, label, 11, Ui.WHITE);
      CommunityIcon icon = new CommunityIcon(i, Ui.accent());
      icon.setBounds(0, 0, Ui.dp(context, 14), Ui.dp(context, 14));
      value.setCompoundDrawables(icon, null, null, null); value.setCompoundDrawablePadding(Ui.dp(context, 4));
      value.setContentDescription(count + " " + keys[i]); row.addView(value);
    }
    return row;
  }

  static void trending(MainActivity activity, LinearLayout into) {
    String company = Company.current.id;
    TextView title = Ui.text(activity, "Trending · " + Company.current.name, 27, Ui.WHITE);
    title.setTypeface(null, Typeface.BOLD); into.addView(title);
    TextView status = Ui.text(activity, "Loading community rankings…", 13, Ui.MUTED);
    into.addView(status); Ui.gap(into, 10);
    androidx.recyclerview.widget.RecyclerView grid = new androidx.recyclerview.widget.RecyclerView(activity);
    androidx.recyclerview.widget.GridLayoutManager layout = new androidx.recyclerview.widget.GridLayoutManager(activity, 4);
    grid.setLayoutManager(layout); grid.setClipToPadding(false);
    into.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
    CommunityApi.IO.execute(() -> {
      try {
        JSONObject rankings = new CommunityApi(activity).request("/api/app/community/highlights?company="
            + CommunityApi.encode(company) + "&rankings=1", null).getJSONObject("rankings");
        activity.runOnUiThread(() -> {
          if (!grid.isAttachedToWindow() || !Company.current.id.equals(company) || activity.isDestroyed()) return;
          status.setText("Most viewed, liked and discussed by this company’s viewers");
          ArrayList<Catalog.Episode> items = new ArrayList<>();
          ArrayList<Object> cells = new ArrayList<>();
          MainActivity.EpisodeAdapter cards = activity.new EpisodeAdapter(items, false);
          String[] keys = {"views", "likes", "comments"};
          String[] headings = {"Most viewed", "Most liked", "Most discussed"};
          for (int group = 0; group < keys.length; group++) {
            cells.add(headings[group]); int before = items.size();
            JSONArray rows = rankings.optJSONArray(keys[group]);
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
              JSONObject row = rows.optJSONObject(i); if (row == null) continue;
              Catalog.Episode episode = Catalog.find(row.optString("id")); if (episode == null) continue;
              cells.add(items.size()); items.add(episode); cards.communityStats.put(episode.id, row);
            }
            if (items.size() == before) cells.add("No activity yet — watch, like or discuss a show to get it started.");
          }
          layout.setSpanSizeLookup(new androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return cells.get(position) instanceof String ? 4 : 1; }
          });
          grid.setAdapter(new androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            @Override public int getItemViewType(int position) { return cells.get(position) instanceof String ? 0 : 1; }
            @Override public int getItemCount() { return cells.size(); }
            @Override public androidx.recyclerview.widget.RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int type) {
              if (type == 1) return cards.onCreateViewHolder(parent, 0);
              TextView heading = Ui.text(activity, "", 18, Ui.WHITE);
              heading.setPadding(Ui.dp(activity, 6), Ui.dp(activity, 22), 0, Ui.dp(activity, 12));
              heading.setTypeface(null, Typeface.BOLD);
              heading.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2));
              return new androidx.recyclerview.widget.RecyclerView.ViewHolder(heading) {};
            }
            @Override public void onBindViewHolder(androidx.recyclerview.widget.RecyclerView.ViewHolder holder, int position) {
              Object cell = cells.get(position);
              if (cell instanceof String) { ((TextView) holder.itemView).setText((String) cell); return; }
              int index = (Integer) cell;
              cards.onBindViewHolder((MainActivity.EpisodeAdapter.Holder) holder, index);
              Catalog.Episode episode = items.get(index);
              holder.itemView.setOnClickListener(view -> activity.details(episode, Catalog.forYear(episode.year, "All", activity.extras())));
            }
          });
        });
      } catch (Exception error) {
        activity.runOnUiThread(() -> { if (grid.isAttachedToWindow()) {
          status.setText("Rankings are temporarily unavailable.");
          into.addView(Ui.button(activity, "Retry", false, activity::render), 2);
        }});
      }
    });
  }

  static void highlights(MainActivity activity, LinearLayout into) {
    String company = Company.current.id;
    LinearLayout section = Ui.vertical(activity); into.addView(section); Ui.gap(section, 18);
    LinearLayout heading = Ui.horizontal(activity); section.addView(heading, Ui.lp(activity, -1, 42));
    TextView title = Ui.text(activity, "HIGHEST LIKED & DISCUSSED EPISODES", 14, Ui.WHITE); title.setTypeface(null, Typeface.BOLD); heading.addView(title);
    Ui.gap(heading, 12); heading.addView(Ui.text(activity, "Voted on by our users", 11, Ui.MUTED));
    TextView status = Ui.text(activity, "Finding community favourites...", 14, Ui.MUTED); section.addView(status);
    CommunityApi.IO.execute(() -> {
      try {
        JSONArray rows = new CommunityApi(activity).request("/api/app/community/highlights?company=" + CommunityApi.encode(company), null).getJSONArray("episodes");
        activity.runOnUiThread(() -> {
          if (!section.isAttachedToWindow() || !Company.current.id.equals(company) || activity.isDestroyed()) return;
          ArrayList<Catalog.Episode> items = new ArrayList<>();
          MainActivity.EpisodeAdapter adapter = activity.new EpisodeAdapter(items, true);
          for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            Catalog.Episode item = Catalog.find(row.optString("id")); if (item == null) continue;
            items.add(item); adapter.communityStats.put(item.id, row);
          }
          if (items.isEmpty()) status.setText("No activity yet. Watch, like or comment on an episode to feature it here.");
          else {
            status.setVisibility(View.GONE);
            androidx.recyclerview.widget.RecyclerView rail = new androidx.recyclerview.widget.RecyclerView(activity);
            rail.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(activity, androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false));
            rail.setAdapter(adapter); rail.setNestedScrollingEnabled(false); rail.setClipToPadding(false);
            section.addView(rail, Ui.lp(activity, -1, 208));
          }
        });
      } catch (Exception e) { activity.runOnUiThread(() -> { if (section.isAttachedToWindow()) status.setText("Community favourites are temporarily unavailable."); }); }
    });
  }
}
