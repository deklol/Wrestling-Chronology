package uk.local.wrestlingchronology;

import android.app.job.*;
import android.content.*;
import java.util.concurrent.*;

/** OS-scheduled metadata checks; no foreground service and no video downloads. */
public final class LibraryJob extends JobService {
  static final int ID = 7297;
  ExecutorService worker;
  Future<?> task;

  static void schedule(Context context) {
    JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    if (!Catalog.prefs(context).getBoolean("auto_refresh", true)) {
      scheduler.cancel(ID);
      return;
    }
    if (scheduler.getPendingJob(ID) != null) return;
    scheduler.schedule(
        new JobInfo.Builder(ID, new ComponentName(context, LibraryJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(CatalogSync.INTERVAL, 60 * 60 * 1000L)
            .setPersisted(true)
            .build());
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    if (Catalog.episodes.isEmpty()) Company.restore(this);
    if (PlayerActivity.playbackActive) {
      Catalog.prefs(this).edit().putBoolean("refresh_deferred", true).apply();
      return false;
    }
    if (CatalogSync.busy || !Catalog.prefs(this).getBoolean("auto_refresh", true)) return false;
    worker = Executors.newSingleThreadExecutor();
    task =
        worker.submit(
            () -> {
              try {
                if (Catalog.episodes.isEmpty()) Company.restore(this);
                Catalog.load(this);
                CatalogSync.refresh(this);
              } catch (Exception error) {
                android.util.Log.i(
                    "WCWCatalog",
                    "BACKGROUND_REFRESH_DEFERRED " + error.getClass().getSimpleName());
              } finally {
                jobFinished(params, false);
                worker.shutdown();
              }
            });
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    if (task != null) task.cancel(true);
    if (worker != null) worker.shutdownNow();
    return false;
  }
}
