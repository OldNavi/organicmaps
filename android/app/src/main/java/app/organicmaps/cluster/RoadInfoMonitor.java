package app.organicmaps.cluster;

import android.location.Location;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import app.organicmaps.sdk.cluster.RoadInfo;
import java.util.function.BiConsumer;

/** Coalesces real GPS fixes on one worker; never tied to frame rendering or provider queries. */
final class RoadInfoMonitor
{
  static final long MAX_FIX_AGE_MS = 5000;
  private final Handler mMain = new Handler(Looper.getMainLooper());
  private final Handler mWorker;
  private final BiConsumer<RoadInfo, Long> mListener;
  private volatile Location mLatest;
  private volatile long mNextRead;
  private long mLastFixNanos;
  private final Runnable mRead = this::read;

  RoadInfoMonitor(BiConsumer<RoadInfo, Long> listener)
  {
    mListener = listener;
    HandlerThread thread = new HandlerThread("RoadInfo");
    thread.start();
    mWorker = new Handler(thread.getLooper());
  }

  void update(Location location)
  {
    if (location.getElapsedRealtimeNanos() <= mLastFixNanos)
      return;
    mLastFixNanos = location.getElapsedRealtimeNanos();
    mLatest = new Location(location);
    mWorker.removeCallbacks(mRead);
    mWorker.postAtTime(mRead, Math.max(SystemClock.uptimeMillis(), mNextRead));
  }

  void invalidate()
  {
    mLatest = null;
    mWorker.removeCallbacks(mRead);
    mLastFixNanos = 0;
  }

  private void read()
  {
    Location location = mLatest;
    if (location == null)
      return;
    mNextRead = SystemClock.uptimeMillis() + 250;
    long age = (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1_000_000;
    RoadInfo info = age < 0 || age > MAX_FIX_AGE_MS || !location.hasAccuracy()
                      ? RoadInfo.EMPTY
                      : RoadInfo.read(location.getLatitude(), location.getLongitude(), location.getAccuracy(),
                                      location.hasSpeed() ? location.getSpeed() : -1,
                                      location.hasBearing() ? location.getBearing() : -1, location.getTime() / 1000.0);
    mMain.post(() -> {
      if (mLatest == location)
        mListener.accept(info, location.getElapsedRealtimeNanos());
    });
  }
}
