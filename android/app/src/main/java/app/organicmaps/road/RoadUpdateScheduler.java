package app.organicmaps.road;

import android.content.Context;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import app.organicmaps.MwmApplication;
import java.util.concurrent.TimeUnit;

/** WorkManager persists checks across process death/reboots; expiry is always read from SQLite at execution. */
final class RoadUpdateScheduler
{
  static final String PROVIDER = "provider";
  static final String COUNTRY = "country";
  private static final Constraints NETWORK =
      new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();

  private RoadUpdateScheduler() {}
  private static String tag(String provider)
  {
    return "road-update:" + provider;
  }

  static void sync(Context context, String provider, boolean configured)
  {
    var work = WorkManager.getInstance(context);
    var prefs = MwmApplication.prefs(context);
    if (!configured || !RoadUpdateSettings.enabled(prefs, provider) || RoadUpdateSettings.needsLogin(prefs, provider))
    {
      work.cancelAllWorkByTag(tag(provider));
      return;
    }
    var periodic = new PeriodicWorkRequest.Builder(RoadUpdateWorker.class, 12, TimeUnit.HOURS)
                       .setInputData(new Data.Builder().putString(PROVIDER, provider).build())
                       .setConstraints(NETWORK)
                       .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                       .addTag(tag(provider))
                       .build();
    work.enqueueUniquePeriodicWork(tag(provider) + ":periodic", ExistingPeriodicWorkPolicy.KEEP, periodic);
    checkNow(context, provider);
  }

  static void checkNow(Context context, String provider)
  {
    var prefs = MwmApplication.prefs(context);
    if (!RoadUpdateSettings.enabled(prefs, provider) || RoadUpdateSettings.needsLogin(prefs, provider))
      return;
    String country = RoadUpdateSettings.currentCountry(prefs, System.currentTimeMillis());
    if (country.isEmpty())
      return;
    var request =
        new OneTimeWorkRequest.Builder(RoadUpdateWorker.class)
            .setInputData(new Data.Builder().putString(PROVIDER, provider).putString(COUNTRY, country).build())
            .setConstraints(NETWORK)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(tag(provider))
            .build();
    WorkManager.getInstance(context).enqueueUniqueWork(tag(provider) + ":" + country, ExistingWorkPolicy.KEEP, request);
  }
}
