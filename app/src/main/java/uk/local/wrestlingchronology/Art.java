package uk.local.wrestlingchronology;

import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.concurrent.*;
import org.json.JSONObject;

final class Art {
  static final ExecutorService work = Executors.newFixedThreadPool(2);
  static final Handler main = new Handler(Looper.getMainLooper());
  static final LruCache<String, Bitmap> cache =
      new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        protected int sizeOf(String key, Bitmap value) {
          return value.getAllocationByteCount();
        }
      };

  static String episodeUrl(Catalog.Episode item, boolean portrait) {
    String[] candidates =
        portrait
            ? new String[] {item.poster, item.thumbnail}
            : new String[] {item.thumbnail, item.poster};
    for (String url : candidates) if (usable(url)) return url;
    for (JSONObject source : item.sources)
      if (source.optString("type").equals("youtube")
          && source.optString("videoId").matches("[A-Za-z0-9_-]{11}"))
        return "https://i.ytimg.com/vi/" + source.optString("videoId") + "/hqdefault.jpg";
    return "";
  }

  static final java.util.Map<String, Bitmap> showCards = new java.util.HashMap<>();
  static void loadEpisode(ImageView view, Catalog.Episode item, boolean portrait) {
    String url = episodeUrl(item, portrait);
    load(view, url);
    if (Company.current != Company.AEW || cache.get(url) != null) return;
    Bitmap card = showCards.get(item.show);
    if (card == null) {
      card = Bitmap.createBitmap(800, 450, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(card);
      Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
      paint.setShader(new LinearGradient(0, 0, 800, 450, 0xff101010, 0xff353535, Shader.TileMode.CLAMP));
      canvas.drawRect(0, 0, 800, 450, paint); paint.setShader(null);
      paint.setColor(0xffd7b65d);canvas.drawRect(50, 375, 750, 380, paint);
      Bitmap logo = BitmapFactory.decodeResource(view.getResources(), R.drawable.logo_aew);
      if (logo != null) { canvas.drawBitmap(logo, null, new Rect(230, 70, 570, 195), paint); }
      paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER);
      paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
      paint.setTextSize(item.show.length() > 20 ? 42 : 56);
      canvas.drawText(item.show.equals("PPV") ? "PAY-PER-VIEW" : item.show.toUpperCase(java.util.Locale.UK), 400, 300, paint);
      showCards.put(item.show, card);
    }
    view.clearColorFilter();view.setPadding(0,0,0,0);
    view.setScaleType(ImageView.ScaleType.CENTER_CROP);view.setImageBitmap(card);
  }

  static boolean usable(String url) {
    // The add-on's explicit missing-image URLs contain the magnifying-glass graphic.
    // Archive item thumbnails are real artwork and must remain eligible.
    return url != null
        && url.startsWith("https://")
        && !url.startsWith("https://aiometadata.elfhosted.com/missing_");
  }

  static void placeholder(ImageView view) {
    view.setScaleType(ImageView.ScaleType.FIT_CENTER);
    Company.current.setLogo(view);
    view.setPadding(
        Ui.dp(view.getContext(), 24),
        Ui.dp(view.getContext(), 16),
        Ui.dp(view.getContext(), 24),
        Ui.dp(view.getContext(), 16));
  }

  static void load(ImageView view, String url) {
    load(view, url, false);
  }

  static void loadHero(ImageView view, String url) {
    load(view, url, true);
  }

  private static void load(ImageView view, String url, boolean hero) {
    view.setTag(url);
    if (hero) {
      view.clearColorFilter();
      view.setPadding(0, 0, 0, 0);
      view.setScaleType(ImageView.ScaleType.CENTER_CROP);
      view.setImageResource(Company.current.hero());
    } else placeholder(view);
    if (!usable(url)) return;
    Bitmap ready = cache.get(url);
    if (ready != null) {
      view.clearColorFilter();
      view.setPadding(0, 0, 0, 0);
      view.setScaleType(ImageView.ScaleType.CENTER_CROP);
      view.setImageBitmap(ready);
      return;
    }
    Context context = view.getContext().getApplicationContext();
    work.execute(
        () -> {
          try {
            File folder = new File(context.getCacheDir(), "art");
            folder.mkdirs();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8"));
            StringBuilder name = new StringBuilder();
            for (byte value : hash) name.append(String.format("%02x", value));
            File file = new File(folder, name.toString());
            if (!file.exists()) {
              File[] entries = folder.listFiles();
              long size = 0;
              if (entries != null) for (File entry : entries) size += entry.length();
              if (size > 40 * 1024 * 1024 && entries != null) {
                java.util.Arrays.sort(
                    entries, java.util.Comparator.comparingLong(File::lastModified));
                for (File entry : entries) {
                  if (size < 28 * 1024 * 1024) break;
                  long length = entry.length();
                  if (entry.delete()) size -= length;
                }
              }
              HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
              connection.setConnectTimeout(12000);
              connection.setReadTimeout(15000);
              try (InputStream input = connection.getInputStream();
                  FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int count, total = 0;
                while ((count = input.read(buffer)) >= 0) {
                  total += count;
                  if (total > 5 * 1024 * 1024) throw new IOException("Artwork too large");
                  output.write(buffer, 0, count);
                }
              } finally {
                connection.disconnect();
              }
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.toString(), bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > 800) options.inSampleSize *= 2;
            Bitmap bitmap = BitmapFactory.decodeFile(file.toString(), options);
            if (bitmap == null) return;
            cache.put(url, bitmap);
            main.post(
                () -> {
                  if (url.equals(view.getTag())) {
                    view.clearColorFilter();
                    view.setPadding(0, 0, 0, 0);
                    view.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    view.setImageBitmap(bitmap);
                  }
                });
          } catch (Exception ignored) {
          }
        });
  }
}
