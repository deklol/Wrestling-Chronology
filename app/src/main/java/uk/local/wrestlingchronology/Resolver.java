package uk.local.wrestlingchronology;

import android.util.Log;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.*;
import org.schabi.newpipe.extractor.localization.*;
import org.schabi.newpipe.extractor.stream.*;

final class Resolver {
  static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/131.0.0.0 Safari/537.36";
  static boolean initialized;

  static synchronized void init() {
    if (!initialized) {
      NewPipe.init(new HttpDownloader(), new Localization("en", "GB"), new ContentCountry("GB"));
      initialized = true;
    }
  }

  static ArrayList<JSONObject> options(
      Catalog.Episode episode, int preference, String override, int quality) {
    ArrayList<JSONObject> result = new ArrayList<>();
    String only =
        override != null
            ? override
            : preference == 2 ? "archive" : preference == 3 ? "youtube" : null;
    for (JSONObject source : episode.sources)
      if (SourcePolicy.eligible(source) && (only == null || only.equals(source.optString("type"))))
        result.add(source);
    boolean youtubeFirst = preference == 1 || "youtube".equals(override);
    result.sort(
        Comparator.comparingInt(SourcePolicy::languagePreference)
            .thenComparingInt(
                (JSONObject source) ->
                    source.optString("type").equals(youtubeFirst ? "youtube" : "archive") ? 0 : 1)
            .thenComparingInt(SourcePolicy::preference)
            .thenComparingInt(source -> rank(source, quality))
            .thenComparingLong(source -> source.optLong("bytes", Long.MAX_VALUE)));
    return result;
  }

  static int rank(JSONObject source, int quality) {
    String value = source.optString("label") + " " + source.optString("filename");
    Matcher match = Pattern.compile("(\\d{3,4})p", Pattern.CASE_INSENSITIVE).matcher(value);
    if (match.find()) {
      int height = Integer.parseInt(match.group(1));
      return height > quality ? 10000 + height : quality - height;
    }
    return 500;
  }

  static final class Resolved {
    final ArrayList<String> videos = new ArrayList<>();
    String audio, provider, label;
  }

  static int height(VideoStream stream) {
    Matcher match = Pattern.compile("(\\d+)").matcher(stream.getResolution());
    return match.find() ? Integer.parseInt(match.group(1)) : 0;
  }

  static int audioRank(AudioStream stream) {
    Locale language = stream.getAudioLocale();
    String id = String.valueOf(stream.getAudioTrackId()),
        name = String.valueOf(stream.getAudioTrackName()).toLowerCase(Locale.UK);
    boolean english =
        language != null
                && !language.getLanguage().isEmpty()
                && !language.getLanguage().equals("und")
            ? "en".equals(language.getLanguage())
            : id.matches("(?i)^en(?:[._-].*)?$") || name.contains("english");
    boolean unknown =
        (language == null
                || language.getLanguage().isEmpty()
                || language.getLanguage().equals("und"))
            && !id.matches("^[a-z]{2,3}[._-].*")
            && (name.equals("null")
                || name.isEmpty()
                || name.contains("original")
                || name.equals("default"));
    if (!english
        && !(unknown
            && (stream.getAudioTrackType() == AudioTrackType.ORIGINAL
                || stream.getAudioTrackType() == null))) return 1000;
    return (english ? 0 : 100)
        + (stream.getAudioTrackType() == AudioTrackType.ORIGINAL
            ? 0
            : stream.getAudioTrackType() == AudioTrackType.DESCRIPTIVE ? 30 : 10);
  }

  static Resolved resolve(JSONObject source, int quality) throws Exception {
    Resolved result = new Resolved();
    result.provider = source.getString("type");
    result.label = source.optString("label");
    if (result.provider.equals("dailymotion")) return Dailymotion.resolve(source);
    if (result.provider.equals("archive")) {
      JSONArray parts = source.optJSONArray("urls");
      if (parts != null)
        for (int i = 0; i < parts.length(); i++) result.videos.add(parts.getString(i));
      else result.videos.add(source.getString("url"));
      for (String url : result.videos)
        if (!url.startsWith("https://archive.org/download/"))
          throw new IOException("Unexpected Archive source");
      return result;
    }
    if (!source.optString("channelId").equals(SourceConfig.channelId)
        && !DirectYoutube.allowed(source.optString("videoId"), source.optString("channelId")))
      throw new IOException("YouTube source is not in the configured official catalogue");
    init();
    String id = source.getString("videoId");
    if (!id.matches("[A-Za-z0-9_-]{11}")) throw new IOException("Invalid video identity");
    StreamInfo info = StreamInfo.getInfo("https://www.youtube.com/watch?v=" + id);
    if (!info.getUploaderUrl().endsWith("/" + source.getString("channelId")))
      throw new IOException("YouTube uploader identity does not match the configured source");
    ArrayList<AudioStream> audio = new ArrayList<>(info.getAudioStreams());
    audio.removeIf(
        stream -> !stream.isUrl() || stream.getFormat() == null || audioRank(stream) >= 1000);
    audio.sort(
        Comparator.comparingInt(Resolver::audioRank)
            .thenComparingInt(stream -> stream.getFormat().getMimeType().contains("mp4") ? 0 : 1)
            .thenComparingInt(stream -> Math.abs(stream.getAverageBitrate() - 128)));
    if (audio.isEmpty()) throw new IOException("No English/original audio track found");
    // Combined formats can contain YouTube's default dub without any language metadata.
    ArrayList<VideoStream> videos = new ArrayList<>(info.getVideoOnlyStreams());
    videos.removeIf(
        stream ->
            !stream.isUrl()
                || height(stream) > quality
                || stream.getFormat() == null
                || !stream.getFormat().getMimeType().contains("mp4")
                || (stream.getCodec() != null
                    && (stream.getCodec().startsWith("av01")
                        || stream.getCodec().startsWith("hev1")
                        || stream.getCodec().startsWith("hvc1"))));
    videos.sort(
        Comparator.comparingInt(Resolver::height)
            .reversed()
            .thenComparing(VideoStream::isVideoOnly));
    if (videos.isEmpty()) throw new IOException("No compatible YouTube video stream");
    VideoStream video = videos.get(0);
    result.videos.add(video.getContent());
    if (video.isVideoOnly()) {
      AudioStream selected = audio.get(0);
      result.audio = selected.getContent();
      Log.i(
          "WCWPlayer",
          "YOUTUBE_TRACK id="
              + id
              + " video="
              + video.getResolution()
              + " audio="
              + selected.getAudioLocale()
              + " type="
              + selected.getAudioTrackType()
              + " bitrate="
              + selected.getAverageBitrate());
    }
    result.label =
        "Official YouTube · "
            + video.getResolution()
            + (audioRank(audio.get(0)) < 100 ? " · English" : " · Original audio");
    return result;
  }

  static final class HttpDownloader extends Downloader {
    @Override
    public Response execute(Request request) throws IOException {
      HttpURLConnection connection = (HttpURLConnection) new URL(request.url()).openConnection();
      connection.setRequestMethod(request.httpMethod());
      connection.setConnectTimeout(12000);
      connection.setReadTimeout(18000);
      connection.setRequestProperty("User-Agent", USER_AGENT);
      connection.setRequestProperty("Accept-Language", "en-GB,en;q=0.9");
      for (Map.Entry<String, List<String>> header : request.headers().entrySet())
        connection.setRequestProperty(header.getKey(), String.join(", ", header.getValue()));
      connection.setRequestProperty("Accept-Encoding", "identity");
      try {
        byte[] body = request.dataToSend();
        if (body != null) {
          connection.setDoOutput(true);
          try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
          }
        }
        int status = connection.getResponseCode();
        InputStream input =
            status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = input == null ? "" : Catalog.read(input);
        return new Response(
            status,
            connection.getResponseMessage(),
            connection.getHeaderFields(),
            response,
            connection.getURL().toString());
      } finally {
        connection.disconnect();
      }
    }
  }
}
