package uk.local.wrestlingchronology;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Optional startup checks; installation always goes through the Fire TV installer. */
final class Updater {
  static final String GITHUB =
      "https://api.github.com/repos/deklol/Wrestling-Chronology/releases/latest";
  static final AtomicBoolean DOWNLOAD = new AtomicBoolean();
  final Activity activity;
  final String targetPackage, currentVersion;
  final int currentCode;
  boolean resumed, checked, checking, pendingAutomatic, installPending;
  volatile boolean cancelled, destroyed;
  UpdateRelease pending, verified;
  ProgressDialog progress;

  Updater(Activity activity) {
    this(activity, BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME);
  }

  Updater(Activity activity, String targetPackage, int currentCode, String currentVersion) {
    this.activity = activity;
    this.targetPackage = targetPackage;
    this.currentCode = currentCode;
    this.currentVersion = currentVersion;
  }

  void onResume() {
    resumed = true;
    if (installPending) {
      installPending = false;
      install();
    } else offer();
  }

  void onPause() {
    resumed = false;
  }

  void destroy() {
    destroyed = true;
    cancelled = true;
    if (progress != null) progress.dismiss();
  }

  void startup() {
    if (checked) return;
    checked = true;
    if (Catalog.prefs(activity).getBoolean("auto_update_check", true)) check(true);
  }

  void check(boolean automatic) {
    if (checking || DOWNLOAD.get()) {
      if (!automatic) toast("An update check or download is already running.");
      return;
    }
    checking = true;
    if (!automatic) toast("Checking GitHub for app updates…");
    new Thread(
            () -> {
              try {
                String feed = Catalog.prefs(activity).getString("update_feed", "").trim();
                UpdateRelease release;
                if (feed.isEmpty()) {
                  JSONObject github = new JSONObject(metadata(GITHUB));
                  UpdateRelease asset = UpdateRelease.github(github);
                  release = asset;
                  JSONArray assets = github.getJSONArray("assets");
                  for (int i = 0; i < assets.length(); i++) {
                    JSONObject item = assets.getJSONObject(i);
                    if (!"update.json".equals(item.optString("name"))) continue;
                    release =
                        UpdateRelease.manifest(
                            new JSONObject(metadata(item.getString("browser_download_url"))));
                    if (!release.version.equals(asset.version)
                        || !release.hash.equals(asset.hash)
                        || !release.url.equals(asset.url))
                      throw new IOException("Release manifest does not match its APK asset");
                  }
                } else release = UpdateRelease.manifest(new JSONObject(metadata(feed)));
                UpdateRelease result = release;
                activity.runOnUiThread(
                    () -> {
                      checking = false;
                      if (destroyed) return;
                      Log.i(
                          "WCWUpdate",
                          "CHECK version=" + result.version + " automatic=" + automatic);
                      String skipped =
                          Catalog.prefs(activity).getString("skipped_update_version", "");
                      if (result.shouldOffer(currentCode, currentVersion, skipped, automatic)) {
                        pending = result;
                        pendingAutomatic = automatic;
                        offer();
                      } else if (!automatic) toast("You're up to date (" + currentVersion + ").");
                    });
              } catch (Exception error) {
                activity.runOnUiThread(
                    () -> {
                      checking = false;
                      if (!automatic) toast("Couldn't check for updates. " + error.getMessage());
                      Log.i("WCWUpdate", "CHECK_FAILED " + error.getClass().getSimpleName());
                    });
              }
            },
            "WCW-UpdateCheck")
        .start();
  }

  void offer() {
    if (!resumed
        || destroyed
        || activity.isFinishing()
        || PlayerActivity.playbackActive
        || pending == null) return;
    UpdateRelease release = pending;
    pending = null;
    String skipped = Catalog.prefs(activity).getString("skipped_update_version", "");
    if (!release.shouldOffer(currentCode, currentVersion, skipped, pendingAutomatic)) return;
    new AlertDialog.Builder(activity)
        .setTitle("Wrestling Chronology " + release.version + " is available")
        .setMessage(
            release.notes.substring(0, Math.min(3500, release.notes.length()))
                + "\n\n"
                + "Your viewing progress will be kept. Fire TV will ask you to confirm"
                + " installation.")
        .setPositiveButton("Update", (dialog, which) -> download(release))
        .setNegativeButton("Later", null)
        .setNeutralButton(
            "Skip this version",
            (dialog, which) -> {
              Catalog.prefs(activity)
                  .edit()
                  .putString("skipped_update_version", release.version)
                  .apply();
              toast(
                  "Version "
                      + release.version
                      + " skipped. You can still use Update app in Settings.");
            })
        .show();
  }

  static HttpURLConnection connect(String url) throws Exception {
    URL target = new URL(url);
    if (!"https".equalsIgnoreCase(target.getProtocol()))
      throw new IOException("Update URL must use HTTPS");
    HttpURLConnection connection = (HttpURLConnection) target.openConnection();
    connection.setConnectTimeout(12000);
    connection.setReadTimeout(20000);
    connection.setRequestProperty("User-Agent", "WCW-Chronology/" + BuildConfig.VERSION_NAME);
    connection.setRequestProperty("Cache-Control", "no-cache");
    try {
      if (connection.getResponseCode() != 200)
        throw new IOException("Update host returned " + connection.getResponseCode());
      if (!"https".equalsIgnoreCase(connection.getURL().getProtocol()))
        throw new IOException("Insecure update redirect");
      return connection;
    } catch (Exception error) {
      connection.disconnect();
      throw error;
    }
  }

  static String metadata(String url) throws Exception {
    HttpURLConnection connection = connect(url);
    try (InputStream input = connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        output.write(buffer, 0, count);
        if (output.size() > 1024 * 1024) throw new IOException("Update metadata too large");
      }
      return output.toString("UTF-8");
    } finally {
      connection.disconnect();
    }
  }

  void download(UpdateRelease release) {
    if (!DOWNLOAD.compareAndSet(false, true)) return;
    cancelled = false;
    progress = new ProgressDialog(activity);
    progress.setTitle("Updating Wrestling Chronology");
    progress.setMessage("Downloading version " + release.version);
    progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
    progress.setMax(100);
    progress.setIndeterminate(true);
    progress.setCanceledOnTouchOutside(false);
    progress.setButton(
        ProgressDialog.BUTTON_NEGATIVE, "Cancel", (dialog, which) -> cancelled = true);
    progress.setOnCancelListener(dialog -> cancelled = true);
    progress.show();
    new Thread(
            () -> {
              HttpURLConnection connection = null;
              File temporary = new File(activity.getCacheDir(), "update-download.apk");
              try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                connection = connect(release.url);
                long length = connection.getContentLengthLong();
                if (length > 80L * 1024 * 1024) throw new IOException("Update too large");
                int lastPercent = -1;
                try (InputStream input = connection.getInputStream();
                    FileOutputStream output = new FileOutputStream(temporary)) {
                  byte[] buffer = new byte[32768];
                  long total = 0;
                  int count;
                  while ((count = input.read(buffer)) != -1) {
                    if (cancelled) throw new IOException("Download cancelled");
                    total += count;
                    if (total > 80L * 1024 * 1024) throw new IOException("Update too large");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                    int percent = length > 0 ? (int) Math.min(100, 100L * total / length) : -1;
                    if (percent != lastPercent) {
                      lastPercent = percent;
                      activity.runOnUiThread(
                          () -> {
                            if (!destroyed && progress != null) {
                              progress.setIndeterminate(percent < 0);
                              progress.setProgress(percent);
                            }
                          });
                    }
                  }
                }
                if (cancelled) throw new IOException("Download cancelled");
                StringBuilder hash = new StringBuilder();
                for (byte value : digest.digest())
                  hash.append(String.format(Locale.ROOT, "%02x", value));
                if (!hash.toString().equals(release.hash))
                  throw new IOException("APK checksum mismatch");
                verify(temporary, release);
                File apk = new File(activity.getCacheDir(), "update.apk");
                if (!temporary.renameTo(apk))
                  throw new IOException("Couldn't save verified update");
                Log.i("WCWUpdate", "VERIFIED version=" + release.version);
                activity.runOnUiThread(
                    () -> {
                      if (progress != null) progress.dismiss();
                      if (destroyed || cancelled) return;
                      verified = release;
                      installPending = true;
                      if (resumed) {
                        installPending = false;
                        install();
                      }
                    });
              } catch (Exception error) {
                temporary.delete();
                Log.w(
                    "WCWUpdate",
                    "DOWNLOAD_FAILED "
                        + error.getClass().getSimpleName()
                        + " "
                        + String.valueOf(error.getMessage()).replaceAll("https?://\\S+", "<url>"));
                activity.runOnUiThread(
                    () -> {
                      if (progress != null) progress.dismiss();
                      if (!cancelled) toast("Update wasn't installed. " + error.getMessage());
                    });
              } finally {
                if (connection != null) connection.disconnect();
                DOWNLOAD.set(false);
              }
            },
            "WCW-UpdateDownload")
        .start();
  }

  @SuppressWarnings("deprecation")
  void verify(File apk, UpdateRelease release) throws Exception {
    PackageManager manager = activity.getPackageManager();
    int flags =
        Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES
            : PackageManager.GET_SIGNATURES;
    PackageInfo installed = manager.getPackageInfo(targetPackage, flags);
    PackageInfo archive = manager.getPackageArchiveInfo(apk.toString(), flags);
    long code =
        archive == null
            ? -1
            : Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
    if (archive == null) throw new IOException("Downloaded APK could not be parsed");
    if (!targetPackage.equals(archive.packageName))
      throw new IOException("APK package name mismatch");
    if (code <= currentCode
        || (release.code > 0 && code != release.code)
        || !release.version.equals(UpdateRelease.normalize(archive.versionName)))
      throw new IOException(
          "APK version mismatch (received " + archive.versionName + ", code " + code + ")");
    if (archive.applicationInfo == null
        || archive.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)
      throw new IOException("APK requires a newer Android version");
    if (!sameSigners(installed, archive)) throw new IOException("APK signing certificate mismatch");
  }

  void install() {
    if (destroyed || verified == null || !resumed) return;
    if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
      new AlertDialog.Builder(activity)
          .setTitle("Allow Wrestling Chronology to install updates")
          .setMessage(
              "Enable installation for Wrestling Chronology in Fire TV settings, then return to"
                  + " finish the update.")
          .setPositiveButton(
              "Open settings",
              (dialog, which) -> {
                try {
                  installPending = true;
                  activity.startActivity(
                      new Intent(
                          Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                          Uri.parse("package:" + activity.getPackageName())));
                } catch (Exception error) {
                  installPending = false;
                  toast(
                      "Enable Apps from Unknown Sources for Wrestling Chronology in Fire TV"
                          + " settings, then use Update app again.");
                }
              })
          .setNegativeButton("Later", null)
          .show();
      return;
    }
    try {
      Uri uri = Uri.parse("content://" + activity.getPackageName() + ".updates/package.apk");
      Intent install =
          new Intent(Intent.ACTION_INSTALL_PACKAGE)
              .setDataAndType(uri, "application/vnd.android.package-archive")
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      install.setClipData(ClipData.newRawUri("WCW update", uri));
      activity.startActivity(install);
    } catch (Exception error) {
      toast("Fire TV couldn't open its package installer. Your current app is unchanged.");
    }
  }

  void toast(String message) {
    if (!destroyed && !activity.isFinishing())
      Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
  }

  @SuppressWarnings("deprecation")
  static boolean sameSigners(PackageInfo installed, PackageInfo archive) {
    Signature[] current, update;
    if (Build.VERSION.SDK_INT >= 28) {
      // Some Fire OS package parsers populate only the legacy field for APK archives.
      current =
          installed.signingInfo != null
              ? installed.signingInfo.getApkContentsSigners()
              : installed.signatures;
      update =
          archive.signingInfo != null
              ? archive.signingInfo.getApkContentsSigners()
              : archive.signatures;
    } else {
      current = installed.signatures;
      update = archive.signatures;
    }
    if (current == null || update == null || current.length == 0 || current.length != update.length)
      return false;
    return new HashSet<>(Arrays.asList(current)).equals(new HashSet<>(Arrays.asList(update)));
  }
}
