package app.organicmaps.sdk.road;

import androidx.annotation.MainThread;
import androidx.annotation.WorkerThread;

/** Preparation methods run on the same persistent database worker. Publication swaps an immutable index. */
public final class RoadEvents
{
  private RoadEvents() {}

  public static native long[] nativeGetState();

  @WorkerThread
  public static native void nativeBeginIndex();
  @WorkerThread
  public static native void nativeAppendIndex(double[] values, String[] identities, long importedAt);
  @WorkerThread
  public static native void nativePublishIndex();
  @WorkerThread
  public static native void nativeDiscardPrepared();
  @WorkerThread
  public static native String[] nativeCountriesNear(double latitude, double longitude);
  @MainThread
  public static native void nativeConfigure(boolean enabled, boolean warnings, int visibleKinds, int[] minZooms);
  @MainThread
  public static native void nativeSetCoverageVisible(boolean visible);

  @MainThread
  public static native void nativeClearCoverage();

  @MainThread
  public static native void nativeFocusCoveragePreview();
}
