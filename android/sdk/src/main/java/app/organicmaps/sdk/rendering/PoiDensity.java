package app.organicmaps.sdk.rendering;

import androidx.annotation.MainThread;

public final class PoiDensity
{
  public static final int LOW = 0;
  public static final int NORMAL = 1;
  public static final int HIGH = 2;

  private PoiDensity() {}

  @MainThread
  public static native int nativeGet(boolean cluster);

  @MainThread
  public static native void nativeSet(boolean cluster, int density);
}
