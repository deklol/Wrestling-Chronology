package uk.local.wcplayer;

import android.content.Context;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

final class Library {
  static ArrayList<JSONObject> read(Context context) {
    ArrayList<JSONObject> rows = new ArrayList<>();
    try {
      JSONArray data = new JSONArray(context.getSharedPreferences("library", 0).getString("entries", "[]"));
      for (int i = 0; i < data.length(); i++) rows.add(data.getJSONObject(i));
    } catch (Exception ignored) { }
    return rows;
  }

  static void save(Context context, ArrayList<JSONObject> rows) {
    JSONArray data = new JSONArray();
    for (JSONObject row : rows) data.put(row);
    context.getSharedPreferences("library", 0).edit().putString("entries", data.toString()).apply();
  }

  static JSONObject find(Context context, String id) {
    for (JSONObject row : read(context)) if (row.optString("id").equals(id)) return row;
    return null;
  }
}
