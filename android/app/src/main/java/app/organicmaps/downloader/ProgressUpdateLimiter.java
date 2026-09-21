package app.organicmaps.downloader;

/** Limits redundant progress work; status/error/completion callbacks bypass this gate. */
final class ProgressUpdateLimiter
{
  private final long mIntervalMillis;
  private long mNextUpdate;

  ProgressUpdateLimiter(long intervalMillis)
  {
    mIntervalMillis = intervalMillis;
  }

  boolean shouldUpdate(long nowMillis)
  {
    if (nowMillis < mNextUpdate)
      return false;
    mNextUpdate = nowMillis + mIntervalMillis;
    return true;
  }
}
