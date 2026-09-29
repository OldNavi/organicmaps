package app.organicmaps.downloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.SplashActivity;

public final class UsbMapImportService extends Service
{
  private static final String CHANNEL = "usb_maps";
  private static final int NOTIFICATION = 704;
  private UsbMapImportManager mManager;
  private boolean mStarted;
  private final Runnable mUpdate =
      () -> ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification());
  @Override
  public void onCreate()
  {
    super.onCreate();
    mManager = MwmApplication.from(this).getUsbMapImport();
    if (Build.VERSION.SDK_INT >= 26)
      ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
          .createNotificationChannel(
              new NotificationChannel(CHANNEL, getString(R.string.usb_maps_title), NotificationManager.IMPORTANCE_LOW));
  }
  private Notification notification()
  {
    PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, SplashActivity.class),
                                                   PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(app.organicmaps.branding.R.drawable.ic_splash)
        .setContentTitle(getString(R.string.usb_maps_title))
        .setContentText(mManager.state() == UsbMapImportManager.State.WAITING ? getString(R.string.usb_maps_waiting)
                                                                              : mManager.currentFile())
        .setProgress(1000, mManager.progress(), mManager.state() == UsbMapImportManager.State.WAITING)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setContentIntent(open)
        .build();
  }
  @Override
  public int onStartCommand(Intent intent, int flags, int startId)
  {
    ServiceCompat.startForeground(this, NOTIFICATION, notification(),
                                  Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0);
    if (!mStarted)
    {
      mStarted = true;
      mManager.listen(mUpdate);
      mManager.copyMaps();
    }
    return START_NOT_STICKY;
  }
  @Override
  public void onTimeout(int startId, int foregroundServiceType)
  {
    mManager.cancel();
    stopSelf();
  }

  @Override
  public void onDestroy()
  {
    mManager.unlisten(mUpdate);
    stopForeground(true);
    super.onDestroy();
  }
  @Override
  public IBinder onBind(Intent intent)
  {
    return null;
  }
}
