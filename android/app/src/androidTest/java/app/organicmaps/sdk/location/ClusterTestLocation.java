package app.organicmaps.sdk.location;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Process;
import android.os.SystemClock;

/** Requires an explicit MOCK_LOCATION app-op; always restore the original GPS provider after the test. */
@SuppressLint("MissingPermission") // The runner explicitly enables MOCK_LOCATION and grants location permission.
@SuppressWarnings("deprecation")
public final class ClusterTestLocation implements AutoCloseable
{
  private final LocationManager mManager;

  public static boolean isAllowed(Context context)
  {
    AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
    return ops.checkOpNoThrow("android:mock_location", Process.myUid(), context.getPackageName())
 == AppOpsManager.MODE_ALLOWED;
  }

  public ClusterTestLocation(Context context)
  {
    mManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    mManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true,
                             ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE);
    try
    {
      mManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
    }
    catch (RuntimeException e)
    {
      close();
      throw e;
    }
  }

  public void update(double latitude, double longitude)
  {
    Location location = new Location(LocationManager.GPS_PROVIDER);
    location.setTime(System.currentTimeMillis());
    location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
    location.setLatitude(latitude);
    location.setLongitude(longitude);
    location.setAccuracy(2.0f);
    location.setAltitude(0.0);
    location.setSpeed(10.0f);
    location.setBearing(0.0f);
    mManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location);
  }

  /** A deterministic position for rendering tests; does not replace Android's GPS provider. */
  public static void setCoreLocation(double latitude, double longitude)
  {
    setCoreLocation(latitude, longitude, 0.0f);
  }

  public static void setCoreLocation(double latitude, double longitude, float bearing)
  {
    LocationState.nativeLocationUpdated(System.currentTimeMillis(), latitude, longitude, 2.0f, 0.0, -1.0f, 10.0f,
                                        bearing);
  }

  public static void restore(Context context)
  {
    LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    try
    {
      manager.removeTestProvider(LocationManager.GPS_PROVIDER);
    }
    catch (IllegalArgumentException ignored)
    { /* No test provider remains. */
    }
  }

  @Override
  public void close()
  {
    mManager.removeTestProvider(LocationManager.GPS_PROVIDER);
  }
}
