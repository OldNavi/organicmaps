package app.organicmaps.cluster;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import app.organicmaps.MwmActivity;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.location.LocationUtils;
import app.organicmaps.sdk.location.TrackRecorder;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.util.log.Logger;
import java.io.IOException;

/** Optional binding for hosts that need navigation data before a map Activity is opened. */
public final class NavigationReadyService extends Service
{
  private final Binder mBinder = new Binder();

  @Override
  public void onCreate()
  {
    super.onCreate();
    var application = MwmApplication.from(this);
    if (!application.getOrganicMaps().arePlatformAndCoreInitialized())
    {
      try
      {
        application.initOrganicMaps(null);
      }
      catch (IOException e)
      {
        Logger.e("NavigationReadyService", "Cannot initialize navigation", e);
        stopSelf();
      }
    }
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent)
  {
    var application = MwmApplication.from(this);
    if (!application.getOrganicMaps().arePlatformAndCoreInitialized()
        || !LocationUtils.checkFineLocationPermission(this))
      return null;
    String channel = "external_navigation";
    NotificationManagerCompat.from(this).createNotificationChannel(
        new NotificationChannelCompat.Builder(channel, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(app.organicmaps.routing.R.string.navigation_channel_name))
            .build());
    int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    var open = PendingIntent.getActivity(this, 0, new Intent(this, MwmActivity.class), flags);
    var notification = new NotificationCompat.Builder(this, channel)
                           .setSmallIcon(app.organicmaps.branding.R.drawable.ic_splash)
                           .setContentTitle(getString(app.organicmaps.branding.R.string.app_name))
                           .setContentIntent(open)
                           .setOngoing(true)
                           .setOnlyAlertOnce(true)
                           .build();
    try
    {
      ServiceCompat.startForeground(this, 7302, notification,
                                    Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0);
      application.getLocationHelper().setExternalRoadInfoActive(true);
      application.getLocationHelper().start();
    }
    catch (SecurityException e)
    {
      Logger.e("NavigationReadyService", "Location session is not permitted", e);
      releaseLocation();
      return null;
    }
    return mBinder;
  }

  @Override
  public boolean onUnbind(Intent intent)
  {
    releaseLocation();
    return false;
  }

  private void releaseLocation()
  {
    var application = MwmApplication.from(this);
    application.getLocationHelper().setExternalRoadInfoActive(false);
    if (application.getTopActivity() == null && !application.getLocationHelper().hasExternalNavigation()
        && !application.getDisplayManager().isCarDisplayUsed() && !RoutingController.get().isNavigating()
        && !TrackRecorder.nativeIsTrackRecordingEnabled())
      application.getLocationHelper().stop();
    stopForeground(true);
  }

  @Override
  public void onDestroy()
  {
    if (MwmApplication.from(this).getOrganicMaps().arePlatformAndCoreInitialized())
      releaseLocation();
    super.onDestroy();
  }
}
