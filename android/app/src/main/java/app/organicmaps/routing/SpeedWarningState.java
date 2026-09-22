package app.organicmaps.routing;

/** One warning per speeding episode; timestamps are monotonic milliseconds. */
final class SpeedWarningState
{
  private static final long ENTER_DELAY_MS = 1000;
  private static final long REARM_DELAY_MS = 3000;
  private static final long AUDIO_INTERVAL_MS = 30000;
  private static final double REARM_MARGIN_KMH = 2;
  private long mAboveSince = -1;
  private long mBelowSince = -1;
  private long mLastAudio = -1;
  private double mLastLimit;
  private int mLastOffset;
  private boolean mNotified;

  static boolean isExceeded(double speedMps, double limitMps, int offsetKmh)
  {
    return Double.isFinite(speedMps) && speedMps >= 0 && Double.isFinite(limitMps) && limitMps > 0
 && speedMps * 3.6 > limitMps * 3.6 + offsetKmh;
  }

  boolean update(double speedMps, double limitMps, int offsetKmh, long now)
  {
    if (!Double.isFinite(speedMps) || speedMps < 0 || !Double.isFinite(limitMps) || limitMps <= 0)
    {
      resetEpisode();
      return false;
    }
    if (Double.compare(limitMps, mLastLimit) != 0 || offsetKmh != mLastOffset)
    {
      resetEpisode();
      mLastLimit = limitMps;
      mLastOffset = offsetKmh;
    }
    if (!isExceeded(speedMps, limitMps, offsetKmh))
    {
      mAboveSince = -1;
      if (speedMps * 3.6 <= limitMps * 3.6 + offsetKmh - REARM_MARGIN_KMH)
      {
        if (mBelowSince < 0)
          mBelowSince = now;
        if (now - mBelowSince >= REARM_DELAY_MS)
          mNotified = false;
      }
      else
        mBelowSince = -1;
      return false;
    }
    mBelowSince = -1;
    if (mAboveSince < 0)
      mAboveSince = now;
    return !mNotified && now - mAboveSince >= ENTER_DELAY_MS
 && (mLastAudio < 0 || now - mLastAudio >= AUDIO_INTERVAL_MS);
  }

  void notified(long now)
  {
    mNotified = true;
    mLastAudio = now;
  }

  private void resetEpisode()
  {
    mAboveSince = -1;
    mBelowSince = -1;
    mNotified = false;
  }
}
