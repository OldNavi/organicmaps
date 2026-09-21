package app.organicmaps.sdk.sensors;

import android.annotation.SuppressLint;
import android.car.Car;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import androidx.annotation.Keep;
import androidx.annotation.RequiresApi;
import app.organicmaps.sdk.util.log.Logger;

/** Kept separate so non-automotive devices never need to load the optional android.car library. */
@Keep
@RequiresApi(29)
@SuppressWarnings("deprecation") // registerCallback also supports Android 10/11 head units.
@SuppressLint("MissingPermission") // Car API checks each property permission; denied access is handled below.
final class VhalSensorSource implements SensorSource
{
  private final HandlerThread mThread = new HandlerThread("VhalSensor");
  private final Handler mHandler;
  private final SensorSource.Listener mListener;
  private final int mAreaId;
  private final float mRateHz;
  private final SensorConfig mConfig;
  private final int mProperty;
  private volatile boolean mClosed;
  private Car mCar;
  private CarPropertyManager mProperties;
  private final Runnable mPoll = this::poll;
  private final CarPropertyManager.CarPropertyEventCallback mCallback =
      new CarPropertyManager.CarPropertyEventCallback() {
        @Override
        public void onChangeEvent(CarPropertyValue value)
        {
          if (mClosed || value.getPropertyId() != mProperty || value.getAreaId() != mAreaId)
            return;
          if (value.getStatus() != CarPropertyValue.STATUS_AVAILABLE)
          {
            mListener.onUnavailable();
            return;
          }
          mListener.onValue(value.getValue(),
                            mConfig.measurementTime(value.getTimestamp(), SystemClock.elapsedRealtimeNanos(),
                                                    System.currentTimeMillis() * 1_000_000L));
        }

        @Override
        public void onErrorEvent(int propertyId, int areaId)
        {
          if (!mClosed && propertyId == mProperty && areaId == mAreaId)
            mListener.onUnavailable();
        }
      };

  VhalSensorSource(Context context, SensorConfig config, SensorSource.Listener listener)
  {
    mConfig = config;
    mProperty = config.propertyId;
    mAreaId = config.areaId;
    mRateHz = config.samplingRateHz;
    mListener = listener;
    mThread.start();
    mHandler = new Handler(mThread.getLooper());
    mHandler.post(() -> {
      if (mClosed)
        return;
      try
      {
        mCar = Car.createCar(context, mHandler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                             (car, ready) -> mHandler.post(() -> onConnectionChanged(car, ready)));
      }
      catch (RuntimeException | LinkageError e)
      {
        Logger.w("VhalSensor", "Cannot connect Car API", e);
        mListener.onUnavailable();
      }
    });
  }

  private void onConnectionChanged(Car car, boolean ready)
  {
    if (mClosed)
      return;
    mHandler.removeCallbacks(mPoll);
    mListener.onReset();
    mProperties = null;
    if (!ready)
      return;
    try
    {
      mProperties = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
      if (mProperties == null)
        return;
      CarPropertyConfig<?> config = mProperties.getCarPropertyConfig(mProperty);
      if (config == null)
      {
        Logger.w("VhalSensor", "VHAL property unavailable: " + mProperty);
        return;
      }
      float rate = config.getChangeMode() == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS
                     ? Math.max(config.getMinSampleRate(), Math.min(mRateHz, config.getMaxSampleRate()))
                     : CarPropertyManager.SENSOR_RATE_ONCHANGE;
      if (!mProperties.registerCallback(mCallback, mProperty, rate))
      {
        Logger.w("VhalSensor", "VHAL sensor subscription rejected: " + mProperty);
        return;
      }
      CarPropertyValue<?> initial = mProperties.getProperty(mProperty, mAreaId);
      if (initial != null)
        mCallback.onChangeEvent(initial);
      if (mConfig.pollIntervalMs > 0
          && config.getChangeMode() == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE)
        mHandler.postDelayed(mPoll, mConfig.pollIntervalMs);
    }
    catch (RuntimeException e)
    {
      Logger.w("VhalSensor", "Cannot subscribe to VHAL sensor", e);
      mListener.onUnavailable();
    }
  }

  private void poll()
  {
    if (mClosed || mProperties == null)
      return;
    try
    {
      CarPropertyValue<?> value = mProperties.getProperty(mProperty, mAreaId);
      if (value == null)
        mListener.onUnavailable();
      else
        mCallback.onChangeEvent(value); // Preserve the property timestamp, including stale values.
    }
    catch (RuntimeException e)
    {
      mListener.onUnavailable();
    }
    if (!mClosed)
      mHandler.postDelayed(mPoll, mConfig.pollIntervalMs);
  }

  @Override
  public void close()
  {
    if (mClosed)
      return;
    mClosed = true;
    mListener.onUnavailable();
    mHandler.post(() -> {
      try
      {
        if (mProperties != null)
          mProperties.unregisterCallback(mCallback);
      }
      catch (RuntimeException e)
      {
        Logger.w("VhalSensor", "Cannot unsubscribe VHAL sensor", e);
      }
      finally
      {
        try
        {
          if (mCar != null)
            mCar.disconnect();
        }
        catch (RuntimeException e)
        {
          Logger.w("VhalSensor", "Cannot disconnect Car service", e);
        }
        finally
        {
          mThread.quitSafely();
        }
      }
    });
  }
}
