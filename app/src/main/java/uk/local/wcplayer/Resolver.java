package uk.local.wcplayer;

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

  static final class Resolved {
    final ArrayList<String> videos = new ArrayList<>();
    String audio, provider, label;
    boolean hls, dash;
    String fallbackHls;
  }

  static int height(VideoStream stream) {
    Matcher match = Pattern.compile("(\\d+)").matcher(stream.getResolution());
    return match.find() ? Integer.parseInt(match.group(1)) : 0;
  }

  static int audioRank(AudioStream stream) {
    if (stream.getAudioTrackType() == AudioTrackType.ORIGINAL) return 0;
    return stream.getAudioTrackType() == AudioTrackType.DESCRIPTIVE ? 20 : 10;
  }

  static Resolved resolve(String input, int quality) throws Exception {
    android.net.Uri uri = android.net.Uri.parse(input);
    String scheme = uri.getScheme(), host = uri.getHost();
    if ("content".equals(scheme) || "file".equals(scheme)) return direct(input, "Local file");
    if (!"https".equals(scheme) || host == null || uri.getUserInfo() != null)
      throw new IOException("Choose a media file or enter an HTTPS video link.");
    host = host.toLowerCase(Locale.ROOT);
    List<String> path = uri.getPathSegments();
    JSONObject source = new JSONObject();
    if (host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com")) {
      String id = host.equals("youtu.be") ? (path.isEmpty() ? "" : path.get(0)) : uri.getQueryParameter("v");
      if (id == null && path.size() >= 2 && (path.get(0).equals("shorts") || path.get(0).equals("embed"))) id = path.get(1);
      source.put("type", "youtube").put("videoId", id == null ? "" : id);
    } else if (host.equals("dai.ly") || host.equals("dailymotion.com") || host.endsWith(".dailymotion.com")) {
      String id = host.equals("dai.ly") ? (path.isEmpty() ? "" : path.get(0))
          : path.size() >= 2 && path.get(0).equals("video") ? path.get(1) : "";
      source.put("type", "dailymotion").put("videoId", id);
    } else if (host.equals("ok.ru") || host.equals("www.ok.ru")) {
      String id = path.size() >= 2 && (path.get(0).equals("video") || path.get(0).equals("videoembed")) ? path.get(1) : "";
      source.put("type", "okru").put("videoId", id);
    } else {
      if (host.equals("archive.org") && !path.isEmpty() && path.get(0).equals("details")) {
        if (path.size() < 3) throw new IOException("Use the media file link from this archive item.");
        input = "https://archive.org" + uri.getEncodedPath().replaceFirst("^/details/", "/download/");
      }
      return direct(input, host.equals("archive.org") || host.endsWith(".archive.org") ? "Internet Archive" : "Video link");
    }
    return resolveSource(source, Math.max(240, Math.min(1080, quality)));
  }

  private static Resolved direct(String url, String label) {
    Resolved result = new Resolved(); result.provider = "direct"; result.label = label; result.videos.add(url); return result;
  }

  static String read(InputStream input) throws IOException {
    try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] block = new byte[8192]; int count;
      while ((count = in.read(block)) != -1) {
        if (out.size() + count > 4 * 1024 * 1024) throw new IOException("Metadata exceeds its size limit.");
        out.write(block, 0, count);
      }
      return out.toString("UTF-8");
    }
  }

  static String get(String url) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(12000); connection.setReadTimeout(18000);
    connection.setRequestProperty("User-Agent", USER_AGENT);
    try {
      if (connection.getResponseCode() != 200) throw new IOException("The provider could not open this video.");
      return read(connection.getInputStream());
    } finally { connection.disconnect(); }
  }

  private static Resolved resolveSource(JSONObject source, int quality) throws Exception {
    Resolved result = new Resolved();
    result.provider = source.getString("type");
    result.label = source.optString("label");
    if (result.provider.equals("okru")) return Okru.resolve(source, quality);
    if (result.provider.equals("dailymotion")) return Dailymotion.resolve(source);
    init();
    String id = source.getString("videoId");
    if (!id.matches("[A-Za-z0-9_-]{11}")) throw new IOException("Invalid video identity");
    StreamInfo info = StreamInfo.getInfo("https://www.youtube.com/watch?v=" + id);
    ArrayList<AudioStream> audio = new ArrayList<>(info.getAudioStreams());
    audio.removeIf(
        stream -> !stream.isUrl() || stream.getFormat() == null || audioRank(stream) >= 1000);
    audio.sort(
        Comparator.comparingInt(Resolver::audioRank)
            .thenComparingInt(stream -> stream.getFormat().getMimeType().contains("mp4") ? 0 : 1)
            .thenComparingInt(stream -> Math.abs(stream.getAverageBitrate() - 128)));
    if (audio.isEmpty()) throw new IOException("No compatible audio track found");
    // Combined formats can contain YouTube's default dub without any language metadata.
    ArrayList<VideoStream> videos = new ArrayList<>(info.getVideoOnlyStreams());
    videos.removeIf(
        stream ->
            !stream.isUrl()
                || height(stream) > 1080
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
    VideoStream video = videos.stream().filter(candidate -> height(candidate) <= quality)
        .findFirst().orElse(videos.get(videos.size() - 1));
    result.videos.add(video.getContent());
    if (video.isVideoOnly()) {
      AudioStream selected = audio.get(0);
      result.audio = selected.getContent();
    }
    result.label = "YouTube | " + video.getResolution();
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
        String response = input == null ? "" : read(input);
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
