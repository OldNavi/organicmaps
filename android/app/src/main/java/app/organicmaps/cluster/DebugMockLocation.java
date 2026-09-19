package app.organicmaps.cluster;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import app.organicmaps.sdk.util.log.Logger;

/** Debug-only adb commands. The provider verifies the caller before clearing Binder identity. */
@SuppressWarnings("deprecation")
@SuppressLint("MissingPermission") // Explicit MOCK_LOCATION app-op is required by LocationManager at runtime.
final class DebugMockLocation
{
  private static final Handler sHandler = new Handler(Looper.getMainLooper());
  private static LocationManager sManager;
  private static Location sLocation;
  private static final Runnable sPublish = new Runnable() {
    @Override
    public void run()
    {
      synchronized (DebugMockLocation.class)
      {
        if (sLocation == null)
          return;
        try
        {
          publish();
          sHandler.postDelayed(this, 1000);
        }
        catch (SecurityException | IllegalArgumentException e)
        {
          sLocation = null;
          Logger.w("DebugMockLocation", "Mock GPS updates stopped: " + e.getMessage());
        }
      }
    }
  };

  private static double coordinate(Bundle args, String key, double minimum, double maximum)
  {
    Object value = args.get(key);
    double number =
        value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(String.valueOf(value));
    if (!Double.isFinite(number) || number < minimum || number > maximum)
      throw new IllegalArgumentException("Invalid " + key);
    return number;
  }

  static synchronized Bundle set(Context context, Bundle args)
  {
    double latitude = coordinate(args, "latitude", -90, 90);
    double longitude = coordinate(args, "longitude", -180, 180);
    double speed = args.containsKey("speed") ? coordinate(args, "speed", 0, Float.MAX_VALUE) : 0;
    double bearing = args.containsKey("bearing") ? coordinate(args, "bearing", 0, 360) : 0;
    LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    manager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true,
                            ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE);
    manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
    sHandler.removeCallbacks(sPublish);
    sManager = manager;
    sLocation = new Location(LocationManager.GPS_PROVIDER);
    sLocation.setLatitude(latitude);
    sLocation.setLongitude(longitude);
    sLocation.setAccuracy(3.0f);
    sLocation.setSpeed((float) speed);
    sLocation.setBearing((float) bearing);
    publish();
    sHandler.postDelayed(sPublish, 1000);
    Bundle result = new Bundle();
    result.putBoolean("mock", true);
    result.putDouble("latitude", latitude);
    result.putDouble("longitude", longitude);
    result.putDouble("speed", speed);
    return result;
  }

  private static void publish()
  {
    sLocation.setTime(System.currentTimeMillis());
    sLocation.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
    sManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, sLocation);
  }

  static synchronized Bundle clear(Context context)
  {
    sHandler.removeCallbacks(sPublish);
    sLocation = null;
    LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    try
    {
      manager.removeTestProvider(LocationManager.GPS_PROVIDER);
    }
    catch (IllegalArgumentException ignored)
    { /* Already removed. */
    }
    sManager = null;
    Bundle result = new Bundle();
    result.putBoolean("mock", false);
    return result;
  }
}
