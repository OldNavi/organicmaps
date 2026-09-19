package app.organicmaps.sdk.cluster;

import android.content.Context;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import androidx.annotation.NonNull;

/** A passive map with its own native renderer, camera and graphics surface. */
public final class ClusterMap extends SurfaceView implements SurfaceHolder.Callback
{
  private long mHandle;
  private int mZoom = ClusterZoom.DEFAULT;
  private ClusterCamera mCamera = ClusterCamera.DEFAULT;
  private boolean mPoiVisible;
  private boolean mBuildings3d;
  private Runnable mOnUnsupported = () -> {};

  public void setOnUnsupported(Runnable callback)
  {
    mOnUnsupported = callback;
  }

  public ClusterMap(@NonNull Context context)
  {
    super(context);
    setClickable(false);
    setFocusable(false);
    getHolder().addCallback(this);
  }

  public void set3dBuildings(boolean enabled)
  {
    if (mBuildings3d == enabled)
      return;
    mBuildings3d = enabled;
    if (mHandle != 0)
      nativeSet3dBuildings(mHandle, enabled);
  }

  public void setPoiVisible(boolean visible)
  {
    if (mPoiVisible == visible)
      return;
    mPoiVisible = visible;
    if (mHandle != 0)
      nativeSetPoiVisible(mHandle, visible);
  }

  public void setZoom(int zoom)
  {
    setCamera(zoom, mCamera);
  }

  public void setCamera(int zoom, @NonNull ClusterCamera camera)
  {
    if (!ClusterZoom.isValid(zoom))
      throw new IllegalArgumentException("Cluster zoom must be auto (0) or between 1 and 20");
    mZoom = zoom;
    mCamera = camera;
    if (mHandle != 0)
      nativeSetCamera(mHandle, zoom, camera.tilt, camera.anchorX, camera.anchorY);
  }

  /** Last zoom actually applied by the renderer; zero while the surface is unavailable. */
  @androidx.annotation.Keep
  @androidx.annotation.MainThread
  public double getCurrentZoomLevel()
  {
    return mHandle == 0 ? 0.0 : nativeGetCurrentZoomLevel(mHandle);
  }

  /** Requested, rectangular-coverage, coarse and pending tile counts, for diagnostics. */
  @androidx.annotation.Keep
  @androidx.annotation.MainThread
  public long[] getTileStats()
  {
    return mHandle == 0 ? new long[4] : nativeGetTileStats(mHandle);
  }

  /** Actual renderer tilt in degrees. */
  @androidx.annotation.Keep
  @androidx.annotation.MainThread
  public double getCurrentTilt()
  {
    return mHandle == 0 ? 0.0 : nativeGetCurrentTilt(mHandle);
  }

  @Override
  public void surfaceCreated(@NonNull SurfaceHolder holder)
  {
    mHandle = nativeCreate(holder.getSurface(), getResources().getDisplayMetrics().densityDpi, mZoom, mPoiVisible,
                           mBuildings3d, mCamera.tilt, mCamera.anchorX, mCamera.anchorY);
    if (mHandle == 0)
      post(mOnUnsupported);
  }

  @Override
  public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height)
  {
    if (mHandle != 0)
      nativeResize(mHandle, width, height);
  }

  @Override
  public void surfaceDestroyed(@NonNull SurfaceHolder holder)
  {
    release();
  }

  public void release()
  {
    if (mHandle == 0)
      return;
    nativeDestroy(mHandle);
    mHandle = 0;
  }

  private static native long nativeCreate(Surface surface, int dpi, int zoom, boolean showPoi, boolean buildings3d,
                                          double tilt, double anchorX, double anchorY);
  private static native void nativeDestroy(long handle);
  private static native void nativeResize(long handle, int width, int height);
  private static native void nativeSetCamera(long handle, int zoom, double tilt, double anchorX, double anchorY);
  private static native double nativeGetCurrentTilt(long handle);
  private static native long[] nativeGetTileStats(long handle);
  private static native void nativeSetPoiVisible(long handle, boolean visible);
  private static native void nativeSet3dBuildings(long handle, boolean enabled);
  private static native double nativeGetCurrentZoomLevel(long handle);
  public static native double[] nativeGetCameraAhead();
  public static native double[] nativeGetRouteMetrics();
}
