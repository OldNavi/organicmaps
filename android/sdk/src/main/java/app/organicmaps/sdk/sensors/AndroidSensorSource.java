package app.organicmaps.sdk.sensors;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.util.log.Logger;

final class AndroidSensorSource implements SensorSource, SensorEventListener
{
  private final SensorManager mManager;
  private final SensorSource.Listener mListener;
  private final SensorConfig mConfig;
  private final HandlerThread mThread = new HandlerThread("AndroidSensor");
  private volatile boolean mClosed;

  @Nullable
  static SensorSource open(Context context, SensorConfig config, SensorSource.Listener listener)
  {
    SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    Sensor sensor = manager == null ? null : manager.getDefaultSensor(config.sensorType);
    if (sensor == null)
      return null;
    AndroidSensorSource source = new AndroidSensorSource(manager, config, listener);
    source.mThread.start();
    Handler handler = new Handler(source.mThread.getLooper());
    handler.post(() -> {
      if (source.mClosed)
        return;
      listener.onReset();
      try
      {
        if (!manager.registerListener(source, sensor, Math.round(1_000_000 / config.samplingRateHz), handler))
          listener.onUnavailable();
      }
      catch (RuntimeException e)
      {
        Logger.w("AndroidSensor", "Cannot subscribe to " + config.name, e);
        listener.onUnavailable();
      }
    });
    return source;
  }

  private AndroidSensorSource(SensorManager manager, SensorConfig config, SensorSource.Listener listener)
  {
    mManager = manager;
    mConfig = config;
    mListener = listener;
  }

  @Override
  public void onSensorChanged(SensorEvent event)
  {
    if (mClosed)
      return;
    if (event.accuracy < mConfig.minAccuracy)
      mListener.onUnavailable();
    else
      mListener.onValue(event.values.clone(),
                        mConfig.measurementTime(event.timestamp, SystemClock.elapsedRealtimeNanos(),
                                                System.currentTimeMillis() * 1_000_000L));
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy)
  {
    if (!mClosed && accuracy < mConfig.minAccuracy)
      mListener.onUnavailable();
  }

  @Override
  public void close()
  {
    if (mClosed)
      return;
    mClosed = true;
    new Handler(mThread.getLooper()).post(() -> {
      mManager.unregisterListener(this);
      mThread.quitSafely();
    });
  }
}
