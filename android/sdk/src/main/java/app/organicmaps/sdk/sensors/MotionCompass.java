package app.organicmaps.sdk.sensors;

/** Fallback compass: magnetic north from gravity/magnetic field, gyro interpolation between fixes. */
public final class MotionCompass
{
  private MotionSensors.Reading mGravity;
  private MotionSensors.Reading mMagnetic;
  private long mLastAbsolute;
  private long mLastGyro;
  private double mNorth = Double.NaN;

  public void reset()
  {
    mGravity = null;
    mMagnetic = null;
    mLastAbsolute = 0;
    mLastGyro = 0;
    mNorth = Double.NaN;
  }

  public double update(String name, MotionSensors.Reading reading)
  {
    long time = reading.timestampNanos();
    if ("accelerometer".equals(name))
    {
      double length = norm(reading);
      // Reject free fall and strong linear acceleration as a gravity reference.
      if (length < 7 || length > 12)
        return Double.NaN;
      mGravity = reading;
    }
    else if ("magnetometer".equals(name))
      mMagnetic = reading;
    else if ("gyroscope".equals(name))
    {
      long previous = mLastGyro;
      mLastGyro = time;
      if (Double.isNaN(mNorth) || previous == 0 || time <= previous || time - previous > 250_000_000L
          || time < mLastAbsolute || time - mLastAbsolute > 2_000_000_000L || mGravity == null
          || !mGravity.isFresh(time))
        return Double.NaN;
      double yawRate =
          (reading.x() * mGravity.x() + reading.y() * mGravity.y() + reading.z() * mGravity.z()) / norm(mGravity);
      mNorth = wrap(mNorth - yawRate * (time - previous) / 1_000_000_000.0);
      return mNorth;
    }
    else
      return Double.NaN;
    if (mGravity == null || mMagnetic == null || !mGravity.isFresh(time) || !mMagnetic.isFresh(time))
      return Double.NaN;
    double field = norm(mMagnetic);
    if (field < 10 || field > 100)
      return Double.NaN;
    double gx = mGravity.x(), gy = mGravity.y(), gz = mGravity.z();
    double ex = mMagnetic.y() * gz - mMagnetic.z() * gy;
    double ey = mMagnetic.z() * gx - mMagnetic.x() * gz;
    double ez = mMagnetic.x() * gy - mMagnetic.y() * gx;
    double eastNorm = Math.sqrt(ex * ex + ey * ey + ez * ez);
    if (eastNorm < field * norm(mGravity) * 0.1)
      return Double.NaN;
    double northY = (gz * ex - gx * ez) / norm(mGravity);
    double absolute = Math.atan2(ey, northY);
    // Do not manufacture an absolute heading on cars without a magnetometer.
    mNorth = Double.isNaN(mNorth) || time - mLastAbsolute > 2_000_000_000L
               ? absolute
               : wrap(mNorth + wrap(absolute - mNorth) * 0.2);
    mLastAbsolute = time;
    return mNorth;
  }

  private static double norm(MotionSensors.Reading value)
  {
    return Math.sqrt(value.x() * value.x() + value.y() * value.y() + value.z() * value.z());
  }

  private static double wrap(double angle)
  {
    return Math.atan2(Math.sin(angle), Math.cos(angle));
  }
}
