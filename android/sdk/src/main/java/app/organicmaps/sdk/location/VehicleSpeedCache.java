package app.organicmaps.sdk.location;

import androidx.annotation.Nullable;
import java.lang.reflect.Array;
import java.util.function.Consumer;

/** VHAL timestamps use elapsed-realtime nanoseconds, not GPS/wall-clock time. */
final class VehicleSpeedCache
{
  static final long MAX_AGE_NS = 2_000_000_000L;
  static final double MAX_ABS_SPEED_MPS = 75.0;
  static final class Sample
  {
    final double speed;
    final long timestamp;
    Sample(double speed, long timestamp)
    {
      this.speed = speed;
      this.timestamp = timestamp;
    }
  }
  private final Consumer<Sample> mListener;
  private long mLastTimestamp;
  private boolean mTrusted;
  private Sample mSample;

  VehicleSpeedCache()
  {
    this(sample -> {});
  }

  VehicleSpeedCache(Consumer<Sample> listener)
  {
    mListener = listener;
  }

  synchronized void update(Object value, double multiplier, long timestamp, long now)
  {
    if (timestamp > 0 && timestamp <= mLastTimestamp)
      return;
    if (timestamp <= 0 || timestamp > now || now - timestamp > MAX_AGE_NS)
    {
      clear();
      return;
    }
    mLastTimestamp = timestamp;
    Double speed = convert(value, multiplier);
    if (speed == null || (!mTrusted && speed == 0.0))
    {
      clear();
      return;
    }
    mTrusted = true; // A real nonzero measurement arms subsequent zero/stop values.
    mSample = new Sample(speed, timestamp);
    mListener.accept(mSample);
  }

  @Nullable
  synchronized Double get(long now)
  {
    return mSample != null && now >= mSample.timestamp && now - mSample.timestamp <= MAX_AGE_NS ? mSample.speed : null;
  }

  @Nullable
  synchronized Sample sample(long now)
  {
    return get(now) == null ? null : mSample;
  }

  synchronized void clear()
  {
    if (mSample != null)
    {
      mSample = null;
      mListener.accept(null);
    }
  }

  synchronized void reset()
  {
    clear();
    mTrusted = false;
    mLastTimestamp = 0;
  }

  @Nullable
  static Double convert(Object value, double multiplier)
  {
    if (value != null && value.getClass().isArray())
      value = Array.getLength(value) == 0 ? null : Array.get(value, 0);
    if (!(value instanceof Number number) || !Double.isFinite(multiplier) || multiplier <= 0)
      return null;
    double speed = number.doubleValue() * multiplier;
    return Double.isFinite(speed) && Math.abs(speed) <= MAX_ABS_SPEED_MPS ? speed : null;
  }
}
