package uk.local.wrestlingchronology;

import java.util.*;
import org.json.*;

/** UTC programme boundaries are shared by every device; formatting stays local. */
final class TvGuide {
  static final class Channel {
    final String id, name, group;
    final int number;
    final long startupTimeoutMs;
    final List<Slot> slots = new ArrayList<>();

    Channel(JSONObject value) throws JSONException {
      id = value.getString("id");
      name = value.getString("name");
      number = value.getInt("number");
      group = value.optString("group", "All");
      int timeout = value.optInt("startupTimeoutSeconds", id.equals("darkside") ? 20 : 0);
      startupTimeoutMs = timeout > 0 ? Math.max(5, Math.min(60, timeout)) * 1000L : 0;
    }

    Slot at(double time) {
      for (Slot slot : slots) if (slot.start <= time && slot.end > time) return slot;
      return null;
    }
  }

  static final class Slot {
    final Channel channel;
    final String id, programme, episode, company, title, description, artwork, date, kind;
    final double start, end;

    Slot(Channel channel, JSONObject value, JSONObject show) throws JSONException {
      this.channel = channel;
      id = value.getString("id");
      programme = value.getString("programme");
      episode = show.optString("id");
      company = show.optString("company");
      start = value.getDouble("start");
      end = value.getDouble("end");
      title = show.getString("title");
      description = show.optString("description");
      artwork = show.optString("image", show.optString("thumbnail", show.optString("poster")));
      date = show.optString("date");
      kind = show.optString("show");
      if (end <= start) throw new JSONException("Invalid programme length");
    }

    long offset(double now) {
      return Math.max(
          0, Math.min((long) ((end - start) * 1000) - 1000, (long) ((now - start) * 1000)));
    }
  }

  final List<Channel> channels = new ArrayList<>();
  final double start, end, oldest, expires;

  TvGuide(JSONObject data) throws JSONException {
    start = data.getDouble("start");
    end = data.getDouble("end");
    oldest = data.getDouble("oldest");
    expires = data.getDouble("expires");
    JSONObject programmes = data.getJSONObject("programmes"), slots = data.getJSONObject("slots");
    JSONArray list = data.getJSONArray("channels");
    for (int i = 0; i < list.length(); i++) {
      Channel channel = new Channel(list.getJSONObject(i));
      JSONArray entries = slots.optJSONArray(channel.id);
      if (entries == null) continue;
      for (int j = 0; j < entries.length(); j++) {
        JSONObject entry = entries.getJSONObject(j);
        JSONObject programme = programmes.optJSONObject(entry.getString("programme"));
        if (programme != null) channel.slots.add(new Slot(channel, entry, programme));
      }
      channel.slots.sort(Comparator.comparingDouble(slot -> slot.start));
      if (!channel.slots.isEmpty()) channels.add(channel);
    }
  }

  Channel channel(String id) {
    for (Channel c : channels) if (c.id.equals(id)) return c;
    return null;
  }
}
