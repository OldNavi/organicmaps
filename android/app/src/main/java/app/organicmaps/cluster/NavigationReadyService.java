package app.organicmaps.cluster;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import androidx.annotation.Nullable;
import app.organicmaps.MwmApplication;
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
    var app = MwmApplication.from(this);
    if (!app.getOrganicMaps().arePlatformAndCoreInitialized())
    {
      try
      {
        app.initOrganicMaps(null);
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
    return MwmApplication.from(this).getOrganicMaps().arePlatformAndCoreInitialized() ? mBinder : null;
  }
}
