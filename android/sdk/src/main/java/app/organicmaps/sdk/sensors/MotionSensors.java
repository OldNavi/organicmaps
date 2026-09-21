package app.organicmaps.sdk.sensors;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import app.organicmaps.sdk.util.log.Logger;
import java.lang.reflect.Array;
import java.util.HashMap;
import java.util.Map;

/** Three-axis motion inputs, selected from the same profile as vehicle speed. */
@UiThread
public final class MotionSensors implements AutoCloseable
{
  public interface Listener
  {
    void onReading(String name, Reading reading);
    void onUnavailable(String name);
  }

  public record Reading(double x, double y, double z, long timestampNanos)
  {
    public boolean isFresh(long now)
    {
      return timestampNanos > 0 && timestampNanos <= now && now - timestampNanos <= 2_000_000_000L;
    }
  }

  private static final String[] NAMES = {"accelerometer", "gyroscope", "magnetometer"};
  private final Context mContext;
  private final Listener mListener;
  private final Handler mMain = new Handler(Looper.getMainLooper());
  private final Map<String, SensorSource> mSources = new HashMap<>();
  private final Map<String, Reading> mReadings = new HashMap<>();
  private final Map<String, Long> mTimestamps = new HashMap<>();
  private int mGeneration;
  private boolean mStarted;

  public MotionSensors(Context context, Listener listener)
  {
    mContext = context.getApplicationContext();
    mListener = listener;
  }

  public void start()
  {
    if (mStarted)
      return;
    mStarted = true;
    int generation = ++mGeneration;
    var profile = VehicleSensorConfig.get(mContext);
    Logger.i("MotionSensors", "Profile: " + profile.deviceType + "/" + profile.name);
    for (String name : NAMES)
    {
      SensorConfig config = profile.sensors.get(name);
      if (config == null || !config.enabled)
        continue;
      try
      {
        SensorSource source = SensorSource.open(mContext, config, new SensorSource.Listener() {
          @Override
          public void onValue(Object value, long timestampNanos)
          {
            Reading reading = convert(value, config.multiplier, timestampNanos);
            mMain.post(() -> {
              if (generation != mGeneration)
                return;
              if (timestampNanos <= mTimestamps.getOrDefault(name, 0L))
                return;
              if (reading == null || !reading.isFresh(SystemClock.elapsedRealtimeNanos()))
                unavailable(name);
              else
              {
                mTimestamps.put(name, timestampNanos);
                mReadings.put(name, reading);
                mListener.onReading(name, reading);
              }
            });
          }
          @Override
          public void onUnavailable()
          {
            mMain.post(() -> {
              if (generation == mGeneration)
                unavailable(name);
            });
          }
          @Override
          public void onReset()
          {
            mMain.post(() -> {
              if (generation == mGeneration)
              {
                mTimestamps.remove(name);
                unavailable(name);
              }
            });
          }
        });
        if (source != null)
          mSources.put(name, source);
        else
          Logger.i("MotionSensors", "Unavailable or not permitted: " + name);
      }
      catch (RuntimeException | LinkageError e)
      {
        Logger.w("MotionSensors", "Cannot start " + name, e);
      }
    }
  }

  @Nullable
  public Reading get(String name)
  {
    Reading reading = mReadings.get(name);
    return reading != null && reading.isFresh(SystemClock.elapsedRealtimeNanos()) ? reading : null;
  }

  private void unavailable(String name)
  {
    mReadings.remove(name);
    mListener.onUnavailable(name);
  }

  @Nullable
  static Reading convert(Object value, double multiplier, long timestamp)
  {
    if (value == null || !value.getClass().isArray() || Array.getLength(value) < 3)
      return null;
    double[] axes = new double[3];
    for (int i = 0; i < 3; ++i)
    {
      if (!(Array.get(value, i) instanceof Number number))
        return null;
      axes[i] = number.doubleValue() * multiplier;
      if (!Double.isFinite(axes[i]))
        return null;
    }
    return new Reading(axes[0], axes[1], axes[2], timestamp);
  }

  @Override
  public void close()
  {
    ++mGeneration;
    for (SensorSource source : mSources.values())
      source.close();
    mSources.clear();
    mReadings.clear();
    mTimestamps.clear();
    mStarted = false;
  }
}
