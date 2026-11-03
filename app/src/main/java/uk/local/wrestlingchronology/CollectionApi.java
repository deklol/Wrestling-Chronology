package uk.local.wrestlingchronology;

import android.content.Context;
import java.io.IOException;
import java.net.URI;
import org.json.JSONArray;
import org.json.JSONObject;

/** Catalogue-scoped playback metadata, using the existing installation identity. */
final class CollectionApi {
  static final String PATH = "/api/app/play";
  private static final String[] IDENTITY = {"type", "videoId", "videoIds", "url", "urls"};

  static JSONObject selection(JSONObject source) throws Exception {
    JSONObject result = new JSONObject();
    for (String key : IDENTITY) if (source.has(key)) result.put(key, source.get(key));
    return result;
  }

  static boolean unavailable(CommunityApi.ApiError error) {
    return error.status == 404 || (error.status == 503 && "edge_not_configured".equals(error.code))
        || (error.status == 422 && "catalogue_mismatch".equals(error.code));
  }

  static Resolver.Resolved resolve(Context context, String company, String episode,
      JSONObject source, int quality) throws Exception {
    CommunityApi identity = new CommunityApi(context);
    JSONObject body = new JSONObject().put("company", company).put("id", episode)
        .put("source", selection(source)).put("quality", quality);
    JSONObject response;
    try { response = identity.request(PATH, body); }
    catch (CommunityApi.ApiError error) {
      // A newer public library can precede the server's catalogue refresh.
      // Older, disabled, or unmatched deployments retain device-direct playback.
      // An enabled delivery outage must not switch to a server media relay.
      if (unavailable(error)) return null;
      throw error;
    }
    JSONArray parts = response.getJSONArray("parts");
    if (parts.length() < 1 || parts.length() > 64) throw new IOException("Invalid recording parts");
    for (int index = 0; index < parts.length(); index++) {
      if (!"lazy".equals(parts.getJSONObject(index).optString("type"))) continue;
      JSONObject next = identity.request(PATH, new JSONObject(body.toString()).put("part", index));
      if (!response.getString("provider").equals(next.optString("provider")) || !"edge".equals(next.optString("via")))
        throw new IOException("Unexpected recording part");
      parts.put(index, next.getJSONArray("parts").getJSONObject(0));
    }
    return parse(response, source.optString("type"));
  }

  static Resolver.Resolved parse(JSONObject response, String provider) throws Exception {
    if (!("archive".equals(provider) || "okru".equals(provider))
        || !provider.equals(response.optString("provider")) || !"edge".equals(response.optString("via")))
      throw new IOException("Unexpected collection response");
    Resolver.Resolved result = new Resolver.Resolved();
    result.provider = provider;
    result.label = response.optString("label", provider.equals("archive") ? "Internet Archive" : "OK.ru");
    result.edge = true;
    result.hls = provider.equals("okru");
    result.initialBitrate = response.optLong("initialBitrate", 5000000L);
    JSONArray parts = response.getJSONArray("parts");
    if (parts.length() < 1 || parts.length() > 64) throw new IOException("Invalid recording parts");
    for (int index = 0; index < parts.length(); index++) {
      JSONObject part = parts.getJSONObject(index);
      String url = part.getString("url");
      URI target = new URI(url);
      if (!"https".equals(target.getScheme()) || target.getHost() == null || target.getUserInfo() != null
          || (target.getPort() != -1 && target.getPort() != 443) || !"/media".equals(target.getPath())
          || target.getRawQuery() == null || target.getFragment() != null
          || !"edge".equals(part.optString("delivery"))
          || !(result.hls ? "hls" : "mp4").equals(part.optString("type")))
        throw new IOException("Unexpected collection stream");
      result.videos.add(url);
    }
    return result;
  }
}
