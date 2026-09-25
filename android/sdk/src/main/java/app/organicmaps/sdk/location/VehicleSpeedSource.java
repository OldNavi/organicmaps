package app.organicmaps.sdk.location;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationCompat;
import app.organicmaps.sdk.sensors.SensorConfig;
import app.organicmaps.sdk.sensors.SensorSource;
import app.organicmaps.sdk.sensors.VehicleSensorConfig;
import app.organicmaps.sdk.util.log.Logger;

/** Optional vehicle speed; GNSS coordinates and their update cadence remain authoritative. */
public final class VehicleSpeedSource
{
  public interface Listener
  {
    void onSpeedChanged(double speedMps, long timestampNanos, boolean valid);
  }
  public static final String PERMISSION_SPEED = "android.car.permission.CAR_SPEED";
  public static final String PERMISSION_VENDOR_EXTENSION = "android.car.permission.CAR_VENDOR_EXTENSION";
  private static final String PERMISSION_PREFS = "vehicle_sensors";
  private static final String PERMISSION_REQUESTED = "speed_permission_requested";
  private final Context mContext;
  private final Handler mMain = new Handler(Looper.getMainLooper());
  private final Listener mListener;
  private final String mSensorName;
  private int mGeneration;
  private VehicleSpeedCache mCache = new VehicleSpeedCache();
  @Nullable
  private AutoCloseable mConnection;

  public VehicleSpeedSource(Context context)
  {
    this(context, (speed, timestamp, valid) -> {});
  }

  public VehicleSpeedSource(Context context, Listener listener)
  {
    this(context, "speed", listener);
  }

  VehicleSpeedSource(Context context, String sensorName, Listener listener)
  {
    mSensorName = sensorName;
    mContext = context.getApplicationContext();
    mListener = listener;
  }

  public static boolean isEnabled(Context context)
  {
    return isEnabled(context, VehicleSensorConfig.get(context).sensor("speed"));
  }

  private static boolean isEnabled(Context context, SensorConfig config)
  {
    return config != null && SensorSource.isPermitted(context, config)
 && (config.type != SensorConfig.Type.VHAL
     || (Build.VERSION.SDK_INT >= 29 && "car".equals(VehicleSensorConfig.get(context).deviceType)));
  }

  public static boolean shouldRequestPermission(Context context)
  {
    if (ContextCompat.checkSelfPermission(context, PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED
        || context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE).getBoolean(PERMISSION_REQUESTED, false))
      return false;
    for (String name : new String[] {"speed", "speedMCU"})
    {
      SensorConfig config = VehicleSensorConfig.get(context).sensors.get(name);
      if (isEnabled(context, config) && !config.privileged && config.type == SensorConfig.Type.VHAL
          && PERMISSION_SPEED.equals(requiredPermission(config.propertyId)))
        return true;
    }
    return false;
  }

  static String requiredPermission(int propertyId)
  {
    return (propertyId & 0xf0000000) == 0x20000000 ? PERMISSION_VENDOR_EXTENSION : PERMISSION_SPEED;
  }

  public static void markPermissionRequested(Context context)
  {
    context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PERMISSION_REQUESTED, true)
        .apply();
  }

  @UiThread
  public void start()
  {
    SensorConfig config = VehicleSensorConfig.get(mContext).sensors.get(mSensorName);
    if (!isEnabled(mContext, config))
    {
      stop();
      return;
    }
    if (config.type == SensorConfig.Type.VHAL
        && ContextCompat.checkSelfPermission(mContext, requiredPermission(config.propertyId))
               != PackageManager.PERMISSION_GRANTED)
    {
      stop();
      return;
    }
    if (mConnection != null)
      return;
    try
    {
      int generation = ++mGeneration;
      mCache = new VehicleSpeedCache(sample -> mMain.post(() -> {
        if (generation != mGeneration || mConnection == null)
          return;
        long now = SystemClock.elapsedRealtimeNanos();
        if (sample == null || now < sample.timestamp || now - sample.timestamp > VehicleSpeedCache.MAX_AGE_NS)
          mListener.onSpeedChanged(0.0, 0, false);
        else
          mListener.onSpeedChanged(sample.speed, sample.timestamp, true);
      }));
      VehicleSpeedCache cache = mCache;
      mConnection = SensorSource.open(mContext, config, new SensorSource.Listener() {
        @Override
        public void onValue(Object value, long timestampNanos)
        {
          cache.update(config.convert(value), 1.0, timestampNanos, SystemClock.elapsedRealtimeNanos());
        }
        @Override
        public void onUnavailable()
        {
          cache.clear();
        }
        @Override
        public void onReset()
        {
          cache.reset();
        }
      });
    }
    catch (RuntimeException | LinkageError e)
    {
      Logger.w("VehicleSpeed", "Configured speed source unavailable; using GNSS speed", e);
    }
  }

  @UiThread
  public void stop()
  {
    ++mGeneration; // Invalidate already queued callbacks from the old connection.
    boolean connected = mConnection != null;
    if (mConnection != null)
    {
      try
      {
        mConnection.close();
      }
      catch (Exception e)
      {
        Logger.w("VehicleSpeed", "Cannot disconnect Car API", e);
      }
      mConnection = null;
    }
    mCache.clear();
    if (connected)
      mListener.onSpeedChanged(0.0, 0, false);
  }

  /** Signed meters/second, or null while unavailable, disconnected or stale. */
  @Nullable
  @UiThread
  public Double getSpeedMetersPerSecond()
  {
    if (mConnection == null)
      return null;
    return mCache.get(SystemClock.elapsedRealtimeNanos());
  }

  @Nullable
  VehicleSpeedCache.Sample getMeasurement()
  {
    return mConnection == null ? null : mCache.sample(SystemClock.elapsedRealtimeNanos());
  }

  @UiThread
  Location applyTo(Location location)
  {
    Double speed = getSpeedMetersPerSecond();
    if (speed == null || LocationCompat.isMock(location))
      return location;
    Location result = new Location(location);
    result.setSpeed((float) Math.abs(speed)); // Location speed is a magnitude; VHAL speed may be signed.
    LocationCompat.removeSpeedAccuracy(result); // GNSS speed accuracy does not describe the VHAL value.
    return result;
  }
}
