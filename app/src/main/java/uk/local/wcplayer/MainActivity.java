package uk.local.wcplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import org.json.JSONObject;

public final class MainActivity extends Activity {
  private static final int PICK_FILES = 1;
  private ArrayList<JSONObject> entries;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    Ui.allowLandscapeRotation(this);
    entries = Library.read(this);
    render();
  }

  @Override protected void onResume() {
    super.onResume();
    entries = Library.read(this);
    render();
  }

  private void render() {
    LinearLayout root = Ui.vertical(this);
    root.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 20));
    root.setBackgroundColor(Ui.BG);
    LinearLayout header = Ui.horizontal(this);
    header.addView(Ui.text(this, "My library", 26, Ui.WHITE));
    Ui.weight(header);
    header.addView(Ui.button(this, "Choose files", true, this::chooseFiles));
    Ui.gap(header, 12);
    header.addView(Ui.button(this, "Add video link", false, () -> edit(null)));
    root.addView(header);
    Ui.gap(root, 18);
    ScrollView scroll = new ScrollView(this);
    LinearLayout list = Ui.vertical(this);
    scroll.addView(list);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    if (entries.isEmpty()) {
      list.addView(Ui.text(this, "Your library is empty. Add your own media files or video links.", 17, Ui.MUTED));
    }
    entries.sort(Comparator.comparing(row -> row.optString("date") + " " + row.optString("title")));
    for (JSONObject entry : entries) {
      LinearLayout row = Ui.horizontal(this);
      String detail = entry.optString("title", "Video");
      if (!entry.optString("date").isEmpty()) detail += "  |  " + entry.optString("date");
      if (!entry.optString("category").isEmpty()) detail += "  |  " + entry.optString("category");
      row.addView(Ui.button(this, detail, false, () -> play(entry)), new LinearLayout.LayoutParams(0, -2, 1));
      Ui.gap(row, 10);
      row.addView(Ui.button(this, "Edit", false, () -> edit(entry)));
      list.addView(row); Ui.gap(list, 10);
    }
    setContentView(root);
  }

  private void chooseFiles() {
    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    picker.setType("video/*"); picker.addCategory(Intent.CATEGORY_OPENABLE);
    picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    try { startActivityForResult(picker, PICK_FILES); }
    catch (android.content.ActivityNotFoundException error) {
      Toast.makeText(this, "No file picker is available on this device.", Toast.LENGTH_LONG).show();
    }
  }

  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != PICK_FILES || result != RESULT_OK || data == null) return;
    if (data.getClipData() != null) {
      for (int i = 0; i < data.getClipData().getItemCount(); i++) addFile(data.getClipData().getItemAt(i).getUri(), data.getFlags());
    } else if (data.getData() != null) addFile(data.getData(), data.getFlags());
    Library.save(this, entries); render();
  }

  private void addFile(Uri uri, int flags) {
    if (entries.size() >= 5000) return;
    for (JSONObject row : entries) if (row.optString("url").equals(uri.toString())) return;
    try {
      getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (SecurityException ignored) { }
    String title = "Local video";
    try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) title = cursor.getString(0);
    } catch (Exception ignored) { }
    try {
      entries.add(new JSONObject().put("id", UUID.randomUUID().toString()).put("title", title).put("url", uri.toString()));
    } catch (Exception ignored) { }
  }

  private EditText field(LinearLayout form, String hint, String value) {
    EditText input = new EditText(this);
    input.setTextColor(Ui.WHITE); input.setHintTextColor(Ui.MUTED); input.setSingleLine(true);
    input.setHint(hint); input.setText(value);
    form.addView(input, Ui.lp(this, -1, 48));
    return input;
  }

  private void edit(JSONObject entry) {
    LinearLayout form = Ui.vertical(this);
    int pad = Ui.dp(this, 20); form.setPadding(pad, pad, pad, pad);
    EditText title = field(form, "Title", entry == null ? "" : entry.optString("title"));
    EditText url = field(form, "HTTPS video link", entry == null ? "" : entry.optString("url"));
    url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    if (entry != null && entry.optString("url").startsWith("content:")) url.setEnabled(false);
    EditText date = field(form, "Date (optional)", entry == null ? "" : entry.optString("date"));
    EditText category = field(form, "Category (optional)", entry == null ? "" : entry.optString("category"));
    AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(entry == null ? "Add video" : "Edit video")
        .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", null);
    if (entry != null) builder.setNeutralButton("Remove", (dialog, which) -> {
      entries.remove(entry); Library.save(this, entries); render();
    });
    AlertDialog dialog = builder.create(); dialog.show();
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
      String address = url.getText().toString().trim();
      Uri uri = Uri.parse(address);
      if (address.length() > 4096 || !("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
          || entry != null && address.equals(entry.optString("url")) && address.startsWith("content:"))) {
        url.setError("Choose a local file or enter an HTTPS video link."); return;
      }
      if (entry == null && entries.size() >= 5000) { title.setError("This library is full."); return; }
      try {
        JSONObject row = entry == null ? new JSONObject().put("id", UUID.randomUUID().toString()) : entry;
        row.put("url", address).put("title", title.getText().toString().trim().isEmpty() ? "Video" : title.getText().toString().trim())
            .put("date", date.getText().toString().trim()).put("category", category.getText().toString().trim());
        if (entry == null) entries.add(row);
        Library.save(this, entries); dialog.dismiss(); render();
      } catch (Exception error) { title.setError("Unable to save this entry."); }
    });
  }

  private void play(JSONObject entry) {
    startActivity(new Intent(this, PlayerActivity.class).putExtra("id", entry.optString("id")));
  }
}
