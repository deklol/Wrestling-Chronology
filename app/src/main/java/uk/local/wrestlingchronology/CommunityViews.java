package uk.local.wrestlingchronology;

import android.content.Context;
import android.os.*;
import org.json.JSONObject;

/** One summary per watch; one qualified report plus at most one transient retry. */
final class CommunityViews {
  final CommunityApi api;
  final Handler handler = new Handler(Looper.getMainLooper());
  final PlaybackClock clock = new PlaybackClock();
  String company = "", episode = "", ticket = "";
  int generation, attempts;
  long ticketAt, target = 15000;
  boolean playing, pending, finished, disposed;
  java.util.function.LongConsumer onViews;
  final Runnable qualify = this::schedule;
  CommunityViews(Context context) { api = new CommunityApi(context); }

  void open(String company, String episode) {
    if (this.company.equals(company) && this.episode.equals(episode)) return;
    generation++; this.company = company; this.episode = episode;
    ticket = ""; attempts = 0; target = 15000; pending = false; finished = false; playing = false;
    clock.reset(now(), false); handler.removeCallbacks(qualify);
  }
  void acceptSummary(String company, String episode, JSONObject data) {
    if (disposed || !this.company.equals(company) || !this.episode.equals(episode) || !ticket.isEmpty() || finished) return;
    ticket = data.optString("viewTicket"); ticketAt = now(); pending = false;
    clock.reset(now(), playing); schedule();
  }
  static long now() { return SystemClock.elapsedRealtime(); }
  void summary() {
    final int request = generation; final String c = company, id = episode;
    pending = true;
    CommunityApi.IO.execute(() -> {
      try {
        JSONObject data = api.read(c, id, "&summary=1");
        handler.post(() -> {
          if (disposed || request != generation) return;
          pending = false; ticket = data.optString("viewTicket"); ticketAt = now();
          // Start after receipt so server ticket has aged at least 15 seconds too.
          clock.reset(now(), playing); schedule();
        });
      } catch (Exception error) { handler.post(() -> { if (request == generation) { pending = false; finished = true; } }); }
    });
  }
  void playing(boolean value) {
    clock.playing(value, now()); playing = value; schedule();
  }
  void schedule() {
    handler.removeCallbacks(qualify);
    if (disposed || finished || !playing || pending || ticket.isEmpty()) return;
    if (now() - ticketAt >= 21500000) { ticket = ""; clock.reset(now(), playing); summary(); return; }
    long remaining = target - clock.elapsed(now());
    if (remaining > 0) { handler.postDelayed(qualify, remaining); return; }
    pending = true; attempts++;
    final int request = generation; final String c = company, id = episode, t = ticket;
    CommunityApi.IO.execute(() -> {
      try {
        JSONObject result = api.write(c, id, new JSONObject().put("action", "view").put("viewTicket", t));
        handler.post(() -> { if (!disposed && request == generation) {
          pending = false; finished = true;
          if (onViews != null) onViews.accept(result.optLong("views"));
        } });
      } catch (Exception error) { handler.post(() -> {
        if (disposed || request != generation) return;
        pending = false;
        boolean transientFailure = !(error instanceof CommunityApi.ApiError) || ((CommunityApi.ApiError) error).status >= 500 || ((CommunityApi.ApiError) error).status == 429;
        if (attempts >= 2 || !transientFailure) finished = true;
        else { target = clock.elapsed(now()) + 5000; schedule(); }
      }); }
    });
  }
  void dispose() { disposed = true; generation++; handler.removeCallbacksAndMessages(null); }
}
