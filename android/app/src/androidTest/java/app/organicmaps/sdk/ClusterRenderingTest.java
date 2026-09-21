package app.organicmaps.sdk;

import static org.junit.Assert.*;

import android.app.Presentation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.cluster.ClusterCamera;
import app.organicmaps.sdk.cluster.ClusterMap;
import app.organicmaps.sdk.location.ClusterTestLocation;
import app.organicmaps.sdk.routing.RouteMarkType;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.util.ThemeSwitcher;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Exercises real GL renderers on offscreen displays without replacing the vehicle's cluster window. */
public class ClusterRenderingTest
{
  private static final class Output implements AutoCloseable
  {
    final HandlerThread readerThread = new HandlerThread("ClusterTestReader");
    final ImageReader reader;
    final VirtualDisplay display;
    final AtomicLong frames = new AtomicLong();
    final AtomicLong checksum = new AtomicLong();
    final AtomicLong distinctFrames = new AtomicLong();
    final AtomicReference<float[]> marker = new AtomicReference<>();
    final AtomicReference<int[]> imageColors = new AtomicReference<>();

    Output(Context context, int width, int height, int dpi)
    {
      readerThread.start();
      reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
      reader.setOnImageAvailableListener(source -> {
        try (Image image = source.acquireLatestImage())
        {
          if (image == null)
            return;
          Image.Plane plane = image.getPlanes()[0];
          ByteBuffer pixels = plane.getBuffer();
          long hash = 1;
          for (int y = height / 4; y < height * 3 / 4; y += 7)
            for (int x = width / 4; x < width * 3 / 4; x += 7)
            {
              int offset = y * plane.getRowStride() + x * plane.getPixelStride();
              hash = hash * 31 + pixels.getInt(offset);
            }
          long sumX = 0, sumY = 0;
          int count = 0;
          int[] colors = new int[width / 2 * (height / 2)];
          for (int y = 0; y < height; y += 2)
            for (int x = 0; x < width; x += 2)
            {
              int offset = y * plane.getRowStride() + x * plane.getPixelStride();
              int r = pixels.get(offset) & 255, g = pixels.get(offset + 1) & 255, b = pixels.get(offset + 2) & 255;
              colors[y / 2 * (width / 2) + x / 2] = 0xff000000 | r << 16 | g << 8 | b;
              // Match the bright arrow face, excluding tan POI symbols and pale yellow roads.
              if (r > 210 && g > 175 && b < 80)
              {
                sumX += x;
                sumY += y;
                ++count;
              }
            }
          imageColors.set(colors);
          marker.set(count >= 8 ? new float[] {(float) sumX / count / width, (float) sumY / count / height} : null);
          if (checksum.getAndSet(hash) != hash)
            distinctFrames.incrementAndGet();
          frames.incrementAndGet();
        }
      }, new Handler(readerThread.getLooper()));
      DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
      display = manager.createVirtualDisplay(
          "OrganicMaps rendering test", width, height, dpi, reader.getSurface(),
          DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
      assertNotNull(display);
    }

    void save(Context context, String name) throws IOException
    {
      int[] colors = imageColors.get();
      if (colors == null)
        return;
      var bitmap = android.graphics.Bitmap.createBitmap(colors, reader.getWidth() / 2, reader.getHeight() / 2,
                                                        android.graphics.Bitmap.Config.ARGB_8888);
      try (var stream = new java.io.FileOutputStream(new java.io.File(context.getFilesDir(), name)))
      {
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream);
      }
      bitmap.recycle();
    }

    int id()
    {
      return display.getDisplay().getDisplayId();
    }

    @Override
    public void close()
    {
      display.release();
      reader.setOnImageAvailableListener(null, null);
      readerThread.quitSafely();
      try
      {
        readerThread.join(5000);
      }
      catch (InterruptedException e)
      {
        Thread.currentThread().interrupt();
      }
      reader.close();
    }
  }

  private static void main(Runnable action)
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
  }

  private static void awaitFrames(Output output, long count) throws InterruptedException
  {
    long deadline = SystemClock.elapsedRealtime() + 30000;
    while (output.frames.get() <= count && SystemClock.elapsedRealtime() < deadline)
      Thread.sleep(100);
    assertTrue("No rendered frame for display " + output.id(), output.frames.get() > count);
  }

  private static void awaitChanged(Output output, long checksum) throws InterruptedException
  {
    long deadline = SystemClock.elapsedRealtime() + 30000;
    while (output.checksum.get() == checksum && SystemClock.elapsedRealtime() < deadline)
      Thread.sleep(100);
    assertNotEquals("Theme did not redraw display " + output.id(), checksum, output.checksum.get());
  }

  private static void command(Context context, String command, Output output, int zoom)
  {
    Bundle arguments = new Bundle();
    arguments.putInt("displayId", output.id());
    arguments.putInt("zoom", zoom);
    Bundle result =
        context.getContentResolver().call(Uri.parse("content://organicmaps.auto.navi"), command, null, arguments);
    assertNotNull(result);
    assertTrue(result.getBoolean("accepted"));
  }

  private static ClusterMap findCluster(View view, int displayId)
  {
    if (view instanceof ClusterMap && view.getDisplay() != null && view.getDisplay().getDisplayId() == displayId)
      return (ClusterMap) view;
    if (view instanceof ViewGroup)
    {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); ++i)
      {
        ClusterMap found = findCluster(group.getChildAt(i), displayId);
        if (found != null)
          return found;
      }
    }
    return null;
  }

  @androidx.annotation.RequiresApi(29)
  private static ClusterMap awaitZoom(int displayId, int zoom) throws Exception
  {
    AtomicReference<ClusterMap> result = new AtomicReference<>();
    var field = ClusterMap.class.getDeclaredField("mZoom");
    field.setAccessible(true);
    long deadline = SystemClock.elapsedRealtime() + 10000;
    do
    {
      main(() -> {
        for (View root : WindowInspector.getGlobalWindowViews())
        {
          ClusterMap map = findCluster(root, displayId);
          try
          {
            if (map != null && field.getInt(map) == zoom)
              result.set(map);
          }
          catch (IllegalAccessException e)
          {
            throw new AssertionError(e);
          }
        }
      });
      if (result.get() != null)
        return result.get();
      Thread.sleep(50);
    }
    while (SystemClock.elapsedRealtime() < deadline);
    throw new AssertionError("Zoom mode was not delivered to display " + displayId);
  }

  private static void awaitRenderedZoom(ClusterMap map, double expected) throws InterruptedException
  {
    double[] actual = {0};
    long deadline = SystemClock.elapsedRealtime() + 10000;
    do
    {
      main(() -> actual[0] = map.getCurrentZoomLevel());
      if (Math.abs(actual[0] - expected) < 0.05)
        return;
      Thread.sleep(50);
    }
    while (SystemClock.elapsedRealtime() < deadline);
    assertEquals("Renderer did not apply requested zoom", expected, actual[0], 0.05);
  }

  private static void showUri(Context context, Output output, String zoom)
  {
    showUri(context, output, zoom, null);
  }

  private static void showUri(Context context, Output output, String zoom, String poi)
  {
    showUri(context, output, zoom, poi, null);
  }

  private static void showUri(Context context, Output output, String zoom, String poi, String camera)
  {
    Uri uri = Uri.parse("content://organicmaps.auto.navi/show_cluster?displayId=" + output.id()
                        + (zoom == null ? "" : "&zoom=" + zoom) + (poi == null ? "" : "&poi=" + poi)
                        + (camera == null ? "" : "&" + camera));
    try (var cursor = context.getContentResolver().query(uri, null, null, null, null))
    {
      assertNotNull(cursor);
      assertTrue(cursor.moveToFirst());
      assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("accepted")));
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void showClusterDefaultsToAutoAndUpdatesOnlyItsOwnDisplay() throws Exception
  {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var metadata = context.getPackageManager()
                       .getApplicationInfo(context.getPackageName(), android.content.pm.PackageManager.GET_META_DATA)
                       .metaData;
    assertEquals(1, metadata.getInt("gwclub-version"));
    try (Output first = new Output(context, 512, 288, 160); Output second = new Output(context, 640, 360, 240))
    {
      showUri(context, first, null);
      showUri(context, second, "15");
      awaitFrames(first, 3);
      awaitFrames(second, 3);
      ClusterMap original = awaitZoom(first.id(), 0);
      ClusterMap other = awaitZoom(second.id(), 15);
      awaitRenderedZoom(other, 15);
      showUri(context, first, "18");
      assertSame(original, awaitZoom(first.id(), 18));
      awaitRenderedZoom(original, 18);
      awaitRenderedZoom(other, 15);
      showUri(context, first, "auto");
      assertSame(original, awaitZoom(first.id(), 0));
      showUri(context, first, "12");
      awaitZoom(first.id(), 12);
      awaitRenderedZoom(original, 12);
      showUri(context, first, "14");
      showUri(context, first, "17");
      awaitRenderedZoom(original, 17);
      awaitRenderedZoom(other, 15);
      showUri(context, first, null);
      assertSame(original, awaitZoom(first.id(), 0));
      awaitZoom(second.id(), 15);
      command(context, "hide_cluster", first, 0);
      command(context, "hide_cluster", second, 15);
    }
  }

  private static void awaitTilt(ClusterMap map, double expected) throws InterruptedException
  {
    double[] actual = {0};
    long deadline = SystemClock.elapsedRealtime() + 10000;
    do
    {
      main(() -> actual[0] = map.getCurrentTilt());
      if (Math.abs(actual[0] - expected) < 0.05)
        return;
      Thread.sleep(50);
    }
    while (SystemClock.elapsedRealtime() < deadline);
    assertEquals("Renderer did not apply tilt", expected, actual[0], 0.05);
  }

  private static void awaitAnchor(Output output, double x, double y) throws InterruptedException
  {
    long deadline = SystemClock.elapsedRealtime() + 10000;
    float[] marker;
    do
    {
      marker = output.marker.get();
      if (marker != null && Math.abs(marker[0] - x) < 0.05 && Math.abs(marker[1] - y) < 0.065)
        return;
      Thread.sleep(50);
    }
    while (SystemClock.elapsedRealtime() < deadline);
    assertNotNull("No position marker", marker);
    assertEquals("Marker anchor x", x, marker[0], 0.05);
    assertEquals("Marker anchor y", y, marker[1], 0.065);
  }

  private static double awaitMatchingAutoTilt(ClusterMap map, ClusterMap reference) throws InterruptedException
  {
    double[] tilt = new double[2];
    double previous = -1;
    long stableSince = SystemClock.elapsedRealtime();
    long deadline = stableSince + 15000;
    do
    {
      main(() -> {
        tilt[0] = map.getCurrentTilt();
        tilt[1] = reference.getCurrentTilt();
      });
      long now = SystemClock.elapsedRealtime();
      if (Math.abs(tilt[0] - tilt[1]) > 0.05 || Math.abs(tilt[1] - previous) > 0.05)
        stableSince = now;
      else if (now - stableSince >= 500)
        return tilt[1];
      previous = tilt[1];
      Thread.sleep(50);
    }
    while (SystemClock.elapsedRealtime() < deadline);
    throw new AssertionError("Automatic perspectives did not converge: " + tilt[0] + ", " + tilt[1]);
  }

  private static void assertArrowPointsUp(Output output, double x, double y)
  {
    int[] colors = output.imageColors.get();
    assertNotNull(colors);
    int width = output.reader.getWidth() / 2, height = output.reader.getHeight() / 2;
    int minX = width, maxX = -1, minY = height;
    for (int py = Math.max(0, (int) (y * height) - 40); py < Math.min(height, (int) (y * height) + 30); ++py)
      for (int px = Math.max(0, (int) (x * width) - 40); px < Math.min(width, (int) (x * width) + 40); ++px)
      {
        int color = colors[py * width + px];
        if (((color >> 16) & 255) > 170 && ((color >> 8) & 255) > 130 && (color & 255) < 100)
        {
          minX = Math.min(minX, px);
          maxX = Math.max(maxX, px);
          minY = Math.min(minY, py);
        }
      }
    assertTrue("Arrow was not found", maxX >= minX);
    double tipX = 0;
    int count = 0;
    for (int py = minY; py <= Math.min(height - 1, minY + 1); ++py)
      for (int px = minX; px <= maxX; ++px)
      {
        int color = colors[py * width + px];
        if (((color >> 16) & 255) > 170 && ((color >> 8) & 255) > 130 && (color & 255) < 100)
        {
          tipX += px;
          ++count;
        }
      }
    assertEquals("Perspective leaned the arrow sideways", (minX + maxX) / 2.0, tipX / count, 2.5);
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void cameraTiltAndAnchorAreAppliedIndependentlyAndResetToDefaults() throws Exception
  {
    MwmApplication app =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    MapStyle[] originalStyle = new MapStyle[1];
    try (Output first = new Output(app, 768, 432, 160); Output second = new Output(app, 640, 360, 240))
    {
      showUri(app, first, "17", null, "tilt=45&anchor=0.3,0.85");
      showUri(app, second, "17", null, "tilt=0&anchor_x=0.7&anchor_y=0.6");
      awaitFrames(first, 3);
      awaitFrames(second, 3);
      ClusterMap original = awaitZoom(first.id(), 17);
      ClusterMap other = awaitZoom(second.id(), 17);
      main(() -> {
        originalStyle[0] = MapStyle.get();
        MapStyle.set(MapStyle.Clear);
        app.getLocationHelper().stop();
        ClusterTestLocation.setCoreLocation(55.7552, 37.6196);
      });
      awaitTilt(original, 45);
      awaitTilt(other, 0);
      awaitAnchor(first, 0.3, 0.85);
      awaitAnchor(second, 0.7, 0.6);
      assertArrowPointsUp(first, 0.3, 0.85);
      first.save(app, "cluster-camera-tilt45.png");
      second.save(app, "cluster-camera-topdown.png");

      showUri(app, first, "15", null, "tilt=55&anchor=0.65,0.8");
      assertSame(original, awaitZoom(first.id(), 15));
      awaitRenderedZoom(original, 15);
      awaitTilt(original, 55);
      awaitAnchor(first, 0.65, 0.8);
      showUri(app, first, "16", null, "tilt=30&anchor_x=0.65&anchor_y=0.8");
      main(() -> ClusterTestLocation.setCoreLocation(55.7555, 37.62, 90));
      Thread.sleep(1500);
      awaitTilt(original, 30);
      awaitAnchor(first, 0.65, 0.8);
      awaitTilt(other, 0);
      awaitAnchor(second, 0.7, 0.6);
      assertArrowPointsUp(first, 0.65, 0.8);
      first.save(app, "cluster-camera-heading90.png");

      showUri(app, first, "13");
      awaitRenderedZoom(original, 13);
      awaitTilt(original, 0);
      awaitAnchor(first, 0.5, 0.75);
      showUri(app, first, "19", null, "tilt=auto");
      awaitRenderedZoom(original, 19);
      main(() -> assertTrue("Automatic tilt did not resume", original.getCurrentTilt() > 30));
      awaitAnchor(first, 0.5, 0.75);
      awaitTilt(other, 0);
      command(app, "hide_cluster", first, 19);
      command(app, "hide_cluster", second, 17);
    }
    finally
    {
      if (originalStyle[0] != null)
        main(() -> MapStyle.mark(originalStyle[0]));
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void autoZoomUsesAutomaticPerspectiveDespiteManualTilt() throws Exception
  {
    MwmApplication app =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    try (Output first = new Output(app, 768, 432, 160); Output reference = new Output(app, 768, 432, 160))
    {
      showUri(app, first, "auto", null, "tilt=0&anchor=0.65,0.8");
      showUri(app, reference, "auto", null, "tilt=auto");
      awaitFrames(first, 3);
      awaitFrames(reference, 3);
      ClusterMap map = awaitZoom(first.id(), 0);
      ClusterMap automatic = awaitZoom(reference.id(), 0);
      main(() -> {
        app.getLocationHelper().stop();
        ClusterTestLocation.setCoreLocation(55.7552, 37.6196, 90, 5);
      });
      double slowTilt = awaitMatchingAutoTilt(map, automatic);
      assertTrue("Automatic perspective stayed flat", slowTilt > 1);
      awaitAnchor(first, 0.65, 0.8);

      main(() -> ClusterTestLocation.setCoreLocation(55.7555, 37.62, 90, 35));
      double fastTilt = awaitMatchingAutoTilt(map, automatic);
      assertTrue("Perspective must change with speed-based zoom", fastTilt < slowTilt - 1);

      showUri(app, first, "18", null, "tilt=25&anchor=0.65,0.8");
      awaitRenderedZoom(map, 18);
      awaitTilt(map, 25);
      awaitTilt(automatic, fastTilt);
      for (String zoom : new String[] {"auto", "0", null})
      {
        showUri(app, first, zoom, null, "tilt=55&anchor=0.65,0.8");
        assertSame(map, awaitZoom(first.id(), 0));
        awaitTilt(map, fastTilt);
        awaitAnchor(first, 0.65, 0.8);
      }
      command(app, "hide_cluster", first, 0);
      command(app, "hide_cluster", reference, 0);
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void firstRequestAppliesCameraWhenGpsAlreadyExists() throws Exception
  {
    MwmApplication app =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    CountDownLatch initialized = new CountDownLatch(1);
    main(() -> {
      try
      {
        if (!app.initOrganicMaps(initialized::countDown))
          initialized.countDown();
      }
      catch (IOException e)
      {
        throw new AssertionError(e);
      }
    });
    assertTrue(initialized.await(30, TimeUnit.SECONDS));
    main(() -> {
      app.getLocationHelper().stop();
      ClusterTestLocation.setCoreLocation(55.7552, 37.6196);
    });
    try (Output first = new Output(app, 1280, 480, 240))
    {
      showUri(app, first, "18", null, "tilt=5&anchor=0.5,0.85");
      ClusterMap map = awaitZoom(first.id(), 18);
      main(() -> app.getLocationHelper().stop());
      awaitFrames(first, 3);
      awaitRenderedZoom(map, 18);
      awaitTilt(map, 5);
      awaitAnchor(first, 0.5, 0.85);
      Thread.sleep(2000);
      awaitRenderedZoom(map, 18);
      awaitTilt(map, 5);
      command(app, "hide_cluster", first, 18);
    }
    try (Output automatic = new Output(app, 1280, 480, 240))
    {
      showUri(app, automatic, null, null, "tilt=5&anchor=0.5,0.85");
      ClusterMap map = awaitZoom(automatic.id(), 0);
      main(() -> app.getLocationHelper().stop());
      awaitFrames(automatic, 3);
      double[] zoom = {16};
      long deadline = SystemClock.elapsedRealtime() + 10000;
      do
      {
        main(() -> zoom[0] = map.getCurrentZoomLevel());
        if (zoom[0] > 1.0 && Math.abs(zoom[0] - 16.0) > 0.1)
          break;
        Thread.sleep(50);
      }
      while (SystemClock.elapsedRealtime() < deadline);
      assertNotEquals("Initial autozoom was blocked at its seed level", 16.0, zoom[0], 0.1);
      main(() -> assertTrue("Auto zoom retained the supplied manual tilt", Math.abs(map.getCurrentTilt() - 5) > 1));
      awaitAnchor(automatic, 0.5, 0.85);
      command(app, "hide_cluster", automatic, 0);
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void buildingsAreFlatByDefaultAndCanBeEnabledIndependently() throws Exception
  {
    MwmApplication app =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    MapStyle[] originalStyle = new MapStyle[1];
    try (Output first = new Output(app, 768, 432, 160); Output second = new Output(app, 768, 432, 160))
    {
      showUri(app, first, "17", null, "tilt=45");
      showUri(app, second, "17", null, "tilt=45&3d=1");
      awaitFrames(first, 3);
      awaitFrames(second, 3);
      ClusterMap original = awaitZoom(first.id(), 17);
      main(() -> {
        originalStyle[0] = MapStyle.get();
        MapStyle.set(MapStyle.Clear);
        app.getLocationHelper().stop();
        var args = InstrumentationRegistry.getArguments();
        ClusterTestLocation.setCoreLocation(Double.parseDouble(args.getString("latitude", "55.7552")),
                                            Double.parseDouble(args.getString("longitude", "37.6196")));
      });
      Thread.sleep(3000);
      awaitTiles(original);
      long flat = awaitStableImage(first);
      int[] flatImage = first.imageColors.get().clone();
      long raised = awaitStableImage(second);
      android.util.Log.i("ClusterBuildingsTest", "initial=" + java.util.Arrays.toString(tileStats(original)));
      first.save(app, "cluster-buildings-flat.png");
      second.save(app, "cluster-buildings-3d.png");
      assertNotEquals("Building fixture needs a loaded map with buildings", flat, raised);
      showUri(app, first, "17", null, "tilt=45&3d=1");
      assertSame(original, awaitZoom(first.id(), 17));
      awaitChanged(first, flat);
      assertEquals(raised, awaitStableImage(first));
      assertEquals(raised, awaitStableImage(second));
      awaitTilt(original, 45);
      awaitRenderedZoom(original, 17);
      showUri(app, first, "17", null, "tilt=45");
      awaitChanged(first, raised);
      long deadline = SystemClock.elapsedRealtime() + 15000;
      while (SystemClock.elapsedRealtime() < deadline)
      {
        long[] stats = tileStats(original);
        if (stats[0] < stats[1])
          break;
        Thread.sleep(50);
      }
      awaitTiles(original);
      awaitStableImage(first);
      first.save(app, "cluster-buildings-flat-restored.png");
      android.util.Log.i("ClusterBuildingsTest", "restored=" + java.util.Arrays.toString(tileStats(original)));
      assertTrue("Flat vector coverage did not resume", tileStats(original)[0] < tileStats(original)[1]);
      assertNearImageEquals(first, flatImage);
      assertEquals(raised, awaitStableImage(second));
      showUri(app, first, "17", null, "tilt=45&3d=0");
      awaitTiles(original);
      awaitStableImage(first);
      assertNearImageEquals(first, flatImage);
      command(app, "hide_cluster", first, 17);
      command(app, "hide_cluster", second, 17);
    }
    finally
    {
      if (originalStyle[0] != null)
        main(() -> MapStyle.mark(originalStyle[0]));
    }
  }

  private static void assertNearImageEquals(Output output, int[] expected)
  {
    int[] actual = output.imageColors.get();
    assertNotNull(actual);
    int width = output.reader.getWidth() / 2, height = output.reader.getHeight() / 2;
    int changed = 0, count = 0;
    for (int y = height * 3 / 5; y < height; ++y)
      for (int x = 0; x < width; ++x)
      {
        int a = expected[y * width + x], b = actual[y * width + x];
        if (Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) > 8 || Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) > 8
            || Math.abs((a & 255) - (b & 255)) > 8)
          ++changed;
        ++count;
      }
    assertTrue("Near map changed or contains holes: " + changed + "/" + count, changed < count / 100);
  }

  private static long[] tileStats(ClusterMap map)
  {
    AtomicReference<long[]> result = new AtomicReference<>();
    main(() -> result.set(map.getTileStats()));
    return result.get();
  }

  private static void awaitTiles(ClusterMap map) throws InterruptedException
  {
    long deadline = SystemClock.elapsedRealtime() + 60000;
    while (SystemClock.elapsedRealtime() < deadline)
    {
      long[] stats = tileStats(map);
      if (stats[0] > 0 && stats[3] == 0)
        return;
      Thread.sleep(100);
    }
    throw new AssertionError("Tiles did not finish: " + java.util.Arrays.toString(tileStats(map)));
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void latestCameraRequestDoesNotWaitForOldTileWork() throws Exception
  {
    MwmApplication app =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    try (Output first = new Output(app, 1920, 720, 160); Output second = new Output(app, 1280, 480, 160))
    {
      showUri(app, first, "16", "1", "tilt=55&anchor=0.5,0.85");
      showUri(app, second, "17", "1", "tilt=45&anchor=0.5,0.85");
      ClusterMap map = awaitZoom(first.id(), 16);
      ClusterMap other = awaitZoom(second.id(), 17);
      main(() -> {
        app.getLocationHelper().stop();
        ClusterTestLocation.setCoreLocation(55.85, 38.44, 0);
      });
      awaitRenderedZoom(map, 16);
      long start = SystemClock.elapsedRealtime();
      main(() -> {
        for (int i = 0; i < 1000; ++i)
          map.setCamera(14 + i % 6, new ClusterCamera(i % 56, 0.5, 0.85));
        map.setCamera(18, new ClusterCamera(35, 0.5, 0.85));
      });
      awaitRenderedZoom(map, 18);
      awaitTilt(map, 35);
      long elapsed = SystemClock.elapsedRealtime() - start;
      System.out.println("Latest cluster camera applied in " + elapsed + " ms");
      assertTrue("Camera waited for obsolete work: " + elapsed + " ms", elapsed < 2000);
      awaitRenderedZoom(other, 17);
      awaitTilt(other, 45);
      awaitTiles(map);
      awaitStableImage(first);
      first.save(app, "cluster-labels-160dpi.png");
      command(app, "hide_cluster", first, 18);
      command(app, "hide_cluster", second, 17);
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void scaleChangesOnlyTheRequestedDisplayAndDefaultsToOne() throws Exception
  {
    MwmApplication app =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    try (Output first = new Output(app, 1280, 480, 160); Output second = new Output(app, 1280, 480, 160))
    {
      showUri(app, first, "17", "0", "tilt=0&anchor=0.5,0.85");
      showUri(app, second, "17", "0", "tilt=0&anchor=0.5,0.85");
      awaitFrames(first, 3);
      awaitFrames(second, 3);
      ClusterMap map = awaitZoom(first.id(), 17);
      main(() -> {
        app.getLocationHelper().stop();
        ClusterTestLocation.setCoreLocation(55.85, 38.44, 0);
      });
      awaitStableImage(first);
      awaitStableImage(second);
      int normalArrow = arrowPixels(first);
      int[] unchanged = second.imageColors.get().clone();
      first.save(app, "cluster-scale-1.png");
      showUri(app, first, "17", "0", "tilt=0&anchor=0.5,0.85&scale=1.5");
      Thread.sleep(1000);
      awaitRenderedZoom(map, 17);
      awaitStableImage(first);
      assertTrue("DPI scaling must enlarge the cursor", arrowPixels(first) > normalArrow * 1.5);
      first.save(app, "cluster-scale-1.5.png");
      assertImageEquals(second, unchanged);
      showUri(app, first, "17", "0", "tilt=0&anchor=0.5,0.85");
      Thread.sleep(1000);
      awaitRenderedZoom(map, 17);
      awaitStableImage(first);
      assertEquals("Omitting scale must restore 1.0", normalArrow, arrowPixels(first), normalArrow * 0.1);
      command(app, "hide_cluster", first, 17);
      command(app, "hide_cluster", second, 17);
    }
  }

  private static int arrowPixels(Output output)
  {
    int count = 0;
    for (int color : output.imageColors.get())
      if (((color >> 16) & 255) > 210 && ((color >> 8) & 255) > 175 && (color & 255) < 80)
        ++count;
    assertTrue("Position arrow must be rendered", count > 20);
    return count;
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void highTiltDrivingPerformance() throws Exception
  {
    MwmApplication app =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    MapStyle[] originalStyle = new MapStyle[1];
    var args = InstrumentationRegistry.getArguments();
    double latitude = Double.parseDouble(args.getString("latitude", "55.7552"));
    double longitude = Double.parseDouble(args.getString("longitude", "37.6196"));
    try (Output output = new Output(app, 1280, 480, 240))
    {
      showUri(app, output, "18", null, "tilt=55&anchor=0.5,0.85");
      awaitFrames(output, 3);
      ClusterMap map = awaitZoom(output.id(), 18);
      main(() -> {
        originalStyle[0] = MapStyle.get();
        MapStyle.set(MapStyle.Clear);
        app.getLocationHelper().stop();
        ClusterTestLocation.setCoreLocation(latitude, longitude, 0);
      });
      awaitRenderedZoom(map, 18);
      awaitTilt(map, 55);
      awaitTiles(map);
      Thread.sleep(1500);
      long[] before = tileStats(map);
      output.save(app, "cluster-perf-before.png");
      long cpu = android.os.Process.getElapsedCpuTime();
      long wall = SystemClock.elapsedRealtime();
      long frames = output.frames.get(), distinct = output.distinctFrames.get();
      for (int i = 1; i <= 24; ++i)
      {
        final int step = i;
        main(()
                 -> ClusterTestLocation.setCoreLocation(latitude + step * 0.00001, longitude + step * 0.00002,
                                                        step * 3.75f));
        Thread.sleep(250);
      }
      long cpuMs = android.os.Process.getElapsedCpuTime() - cpu;
      long wallMs = SystemClock.elapsedRealtime() - wall;
      long rendered = output.frames.get() - frames;
      long changed = output.distinctFrames.get() - distinct;
      awaitTiles(map);
      Thread.sleep(1500);
      long[] after = tileStats(map);
      assertTrue("High-tilt tile count was not reduced", after[0] * 2 < after[1]);
      assertTrue("Distant cells did not use LOD", after[2] > 0);
      output.save(app, "cluster-perf-after.png");
      String report = "cpu_ms=" + cpuMs + " wall_ms=" + wallMs + " frames=" + rendered + " changed=" + changed
                    + " initial_tiles=" + java.util.Arrays.toString(before)
                    + " final_tiles=" + java.util.Arrays.toString(after);
      android.util.Log.i("ClusterPerformance", report);
      try (var writer = new java.io.FileWriter(new java.io.File(app.getFilesDir(), "cluster-performance.txt")))
      {
        writer.write(report);
      }
      assertTrue("No rendered frames", rendered > 5);
      command(app, "hide_cluster", output, 18);
    }
    finally
    {
      if (originalStyle[0] != null)
        main(() -> MapStyle.mark(originalStyle[0]));
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void adaptiveTilesSurviveTurnsZoomAndBuildingModeChanges() throws Exception
  {
    MwmApplication app =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    MapStyle[] originalStyle = new MapStyle[1];
    try (Output output = new Output(app, 1280, 480, 240))
    {
      showUri(app, output, "18", null, "tilt=55&anchor=0.5,0.85");
      awaitFrames(output, 3);
      ClusterMap map = awaitZoom(output.id(), 18);
      main(() -> {
        originalStyle[0] = MapStyle.get();
        MapStyle.set(MapStyle.Clear);
        app.getLocationHelper().stop();
        ClusterTestLocation.setCoreLocation(55.7552, 37.6196, 0);
      });
      awaitTiles(map);
      Thread.sleep(1500);
      awaitStableImage(output);
      int[] initial = output.imageColors.get().clone();
      for (int pass = 0; pass < 2; ++pass)
        for (int step = 0; step <= 12; ++step)
        {
          final float bearing = (pass == 0 ? step : 12 - step) * 15.0f;
          main(() -> ClusterTestLocation.setCoreLocation(55.7552, 37.6196, bearing));
          Thread.sleep(150);
        }
      awaitTiles(map);
      for (int zoom : new int[] {16, 19, 18})
      {
        showUri(app, output, Integer.toString(zoom), null, "tilt=55&anchor=0.5,0.85");
        awaitRenderedZoom(map, zoom);
        awaitTiles(map);
      }
      showUri(app, output, "18", null, "tilt=45&anchor=0.5,0.85&3d=1");
      awaitTilt(map, 45);
      awaitTiles(map);
      assertEquals(0, tileStats(map)[2]);
      showUri(app, output, "18", null, "tilt=55&anchor=0.5,0.85");
      awaitTilt(map, 55);
      awaitTiles(map);
      Thread.sleep(1500);
      awaitStableImage(output);
      assertTrue(tileStats(map)[2] > 0);
      output.save(app, "cluster-lod-restored.png");
      assertNearImageEquals(output, initial);
      command(app, "hide_cluster", output, 18);
    }
    finally
    {
      if (originalStyle[0] != null)
        main(() -> MapStyle.mark(originalStyle[0]));
    }
  }

  private static long awaitStableImage(Output output) throws InterruptedException
  {
    long deadline = SystemClock.elapsedRealtime() + 15000;
    long hash = output.checksum.get();
    long stableSince = SystemClock.elapsedRealtime();
    do
    {
      Thread.sleep(100);
      long next = output.checksum.get();
      if (next != hash)
      {
        hash = next;
        stableSince = SystemClock.elapsedRealtime();
      }
      if (SystemClock.elapsedRealtime() - stableSince >= 1500)
        return hash;
    }
    while (SystemClock.elapsedRealtime() < deadline);
    throw new AssertionError("Map did not settle on display " + output.id());
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void poiAreHiddenByDefaultAndCanChangeOnOneDisplayWithoutZoom() throws Exception
  {
    MwmApplication app =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    MapStyle[] originalStyle = new MapStyle[1];
    try (Output first = new Output(app, 768, 432, 160); Output second = new Output(app, 768, 432, 160))
    {
      showUri(app, first, "17");
      showUri(app, second, "17", "1");
      awaitFrames(first, 3);
      awaitFrames(second, 3);
      ClusterMap original = awaitZoom(first.id(), 17);
      main(() -> {
        originalStyle[0] = MapStyle.get();
        MapStyle.set(MapStyle.Clear);
        app.getLocationHelper().stop();
        var args = InstrumentationRegistry.getArguments();
        ClusterTestLocation.setCoreLocation(Double.parseDouble(args.getString("latitude", "55.7552")),
                                            Double.parseDouble(args.getString("longitude", "37.6196")));
      });
      Thread.sleep(3000);
      long hidden = awaitStableImage(first);
      long visible = awaitStableImage(second);
      first.save(app, "cluster-poi-hidden.png");
      second.save(app, "cluster-poi-visible.png");
      assertNotEquals("POI fixture needs a loaded map with POI", hidden, visible);

      showUri(app, first, "17", "1");
      assertSame(original, awaitZoom(first.id(), 17));
      awaitChanged(first, hidden);
      assertEquals(visible, awaitStableImage(first));
      assertEquals(visible, awaitStableImage(second));
      awaitRenderedZoom(original, 17);

      // Omission must reset the same existing presentation to the cluster default.
      showUri(app, first, "17");
      awaitChanged(first, visible);
      assertEquals(hidden, awaitStableImage(first));
      assertEquals(visible, awaitStableImage(second));
      showUri(app, first, "17", "0");
      assertEquals(hidden, awaitStableImage(first));
      command(app, "hide_cluster", first, 17);
      command(app, "hide_cluster", second, 17);
    }
    finally
    {
      if (originalStyle[0] != null)
        main(() -> MapStyle.mark(originalStyle[0]));
    }
  }

  @Test
  public void clustersFollowUiModeWithoutMainActivity() throws Exception
  {
    MwmApplication app =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    Configuration originalConfig = new Configuration(app.getResources().getConfiguration());
    Config.UiTheme[] originalTheme = new Config.UiTheme[1];
    MapStyle[] originalStyle = new MapStyle[1];
    CountDownLatch initialized = new CountDownLatch(1);
    main(() -> {
      try
      {
        if (!app.initOrganicMaps(initialized::countDown))
          initialized.countDown();
      }
      catch (IOException e)
      {
        throw new AssertionError(e);
      }
    });
    assertTrue(initialized.await(30, TimeUnit.SECONDS));
    boolean initiallyDark =
        (originalConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    try (Output first = new Output(app, 768, 432, 160); Output second = new Output(app, 768, 432, 160))
    {
      main(() -> {
        assertFalse("Test must start without a primary map renderer", Map.isEngineCreated());
        originalTheme[0] = Config.UiTheme.getUiThemePreference();
        originalStyle[0] = MapStyle.get();
        Config.UiTheme.setUiThemePreference(Config.UiTheme.SYSTEM);
        // Emulate a style saved in the opposite theme before a service-only start.
        MapStyle.mark(initiallyDark ? MapStyle.Clear : MapStyle.Dark);
      });
      showUri(app, first, "16", "0", "tilt=0");
      showUri(app, second, "16", "0", "tilt=0");
      awaitFrames(first, 3);
      awaitFrames(second, 3);
      main(() -> {
        app.getLocationHelper().stop();
        ClusterTestLocation.setCoreLocation(0, 0, 0);
        assertEquals("First cluster must use current system theme", initiallyDark, isDarkMapStyle());
        assertFalse(Map.isEngineCreated());
      });
      awaitStableImage(first);
      awaitStableImage(second);
      for (boolean dark : new boolean[] {!initiallyDark, initiallyDark})
      {
        long firstBefore = first.checksum.get(), secondBefore = second.checksum.get();
        Configuration config = new Configuration(originalConfig);
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                      | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        // Deliver the Application callback, without creating or resuming an Activity.
        main(() -> app.onConfigurationChanged(config));
        awaitChanged(first, firstBefore);
        awaitChanged(second, secondBefore);
        awaitStableImage(first);
        awaitStableImage(second);
        main(() -> {
          assertEquals(dark, isDarkMapStyle());
          assertFalse(Map.isEngineCreated());
        });
        first.save(app, "cluster-system-theme-" + dark + ".png");
      }
      main(() -> {
        Config.UiTheme.setUiThemePreference(Config.UiTheme.LIGHT);
        Configuration config = new Configuration(originalConfig);
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        app.onConfigurationChanged(config);
        assertFalse("Explicit day theme must remain day", isDarkMapStyle());
        Config.UiTheme.setUiThemePreference(Config.UiTheme.DARK);
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
        app.onConfigurationChanged(config);
        assertTrue("Explicit night theme must remain night", isDarkMapStyle());
      });
      command(app, "hide_cluster", first, 16);
      command(app, "hide_cluster", second, 16);
    }
    finally
    {
      main(() -> {
        if (originalTheme[0] != null)
        {
          Config.UiTheme.setUiThemePreference(originalTheme[0]);
          app.onConfigurationChanged(originalConfig);
          MapStyle.mark(originalStyle[0]);
        }
      });
    }
  }

  private static boolean isDarkMapStyle()
  {
    return MapStyle.get() == MapStyle.Dark || MapStyle.get() == MapStyle.VehicleDark
 || MapStyle.get() == MapStyle.OutdoorsDark;
  }

  @Test
  public void clusterCanStartWithoutOpeningTheMainMap() throws Exception
  {
    MwmApplication app =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    try (Output first = new Output(app, 512, 288, 160); Output second = new Output(app, 640, 360, 240))
    {
      command(app, "show_cluster", first, 16);
      command(app, "show_cluster", second, 14);
      awaitFrames(first, 3);
      awaitFrames(second, 3);
      Thread.sleep(1000);
      long firstLight = first.checksum.get();
      long secondLight = second.checksum.get();
      MapStyle[] original = new MapStyle[1];
      first.save(app, "cluster-theme-before.png");
      main(() -> {
        original[0] = MapStyle.get();
        android.util.Log.i("ClusterThemeTest", "Original style=" + original[0] + ", frames=" + first.frames.get());
        boolean dark =
            original[0] == MapStyle.Dark || original[0] == MapStyle.VehicleDark || original[0] == MapStyle.OutdoorsDark;
        MapStyle.set(dark ? MapStyle.Clear : MapStyle.Dark);
      });
      try
      {
        awaitChanged(first, firstLight);
        awaitChanged(second, secondLight);
      }
      finally
      {
        first.save(app, "cluster-theme-after.png");
        main(() -> {
          android.util.Log.i("ClusterThemeTest", "Final style=" + MapStyle.get() + ", frames=" + first.frames.get());
          MapStyle.mark(original[0]);
        });
      }
      command(app, "hide_cluster", first, 16);
      command(app, "hide_cluster", second, 14);
    }
  }

  @Test
  public void restoreMockGpsProvider()
  {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    org.junit.Assume.assumeTrue(ClusterTestLocation.isAllowed(context));
    ClusterTestLocation.restore(context);
  }

  @Test
  public void positionMarkerStaysFixedWhileTheMapFollowsLocation() throws Exception
  {
    MapStyle[] original = new MapStyle[1];
    MwmApplication app =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    org.junit.Assume.assumeTrue("Enable MOCK_LOCATION for this opt-in test", ClusterTestLocation.isAllowed(app));
    try (ClusterTestLocation gps = new ClusterTestLocation(app); Output first = new Output(app, 512, 288, 160);
         Output second = new Output(app, 640, 360, 240))
    {
      command(app, "show_cluster", first, 16);
      command(app, "show_cluster", second, 15);
      awaitFrames(first, 3);
      awaitFrames(second, 3);
      main(() -> {
        original[0] = MapStyle.get();
        MapStyle.set(MapStyle.Clear);
        app.getLocationHelper().restartWithNewMode();
      });
      for (int i = 0; i < 25; ++i)
      {
        gps.update(55.7522, 37.6156);
        Thread.sleep(100);
      }
      main(() -> {
        assertNotNull("LocationManager fix did not reach LocationHelper", app.getLocationHelper().getSavedLocation());
        assertEquals(55.7522, app.getLocationHelper().getSavedLocation().getLatitude(), 0.0001);
      });
      float[] firstBefore = first.marker.get();
      float[] secondBefore = second.marker.get();
      first.save(app, "cluster-marker-first.png");
      second.save(app, "cluster-marker-second.png");
      assertNotNull("No yellow marker on first cluster", firstBefore);
      assertNotNull("No yellow marker on second cluster", secondBefore);
      assertEquals(0.5f, firstBefore[0], 0.06f);
      assertEquals(0.75f, firstBefore[1], 0.12f);
      for (int i = 0; i < 25; ++i)
      {
        gps.update(55.7552, 37.6196);
        Thread.sleep(100);
      }
      first.save(app, "cluster-marker-first-moved.png");
      second.save(app, "cluster-marker-second-moved.png");
      assertNotNull(first.marker.get());
      assertNotNull(second.marker.get());
      assertEquals(firstBefore[0], first.marker.get()[0], 0.03f);
      assertEquals(firstBefore[1], first.marker.get()[1], 0.03f);
      assertEquals(secondBefore[0], second.marker.get()[0], 0.03f);
      assertEquals(secondBefore[1], second.marker.get()[1], 0.03f);
      command(app, "hide_cluster", first, 16);
      command(app, "hide_cluster", second, 15);
    }
    finally
    {
      if (original[0] != null)
        main(() -> MapStyle.mark(original[0]));
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void themeMarkedWhilePrimaryPausedRecolorsRetainedTiles() throws Exception
  {
    MwmApplication app =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    Presentation[] primary = new Presentation[1];
    Map[] primaryMap = new Map[1];
    MapView[] primaryView = new MapView[1];
    MapStyle[] original = new MapStyle[1];
    try (Output mainOutput = new Output(app, 768, 432, 160); Output cluster = new Output(app, 768, 432, 160))
    {
      showUri(app, cluster, "13", "1", "tilt=0");
      awaitFrames(cluster, 3);
      main(() -> {
        original[0] = MapStyle.get();
        app.getLocationHelper().stop();
        ClusterTestLocation.setCoreLocation(55.65, 37.63, 0);
        Presentation window = new Presentation(app, mainOutput.display.getDisplay());
        MapView view = new MapView(window.getContext());
        primaryView[0] = view;
        primaryMap[0] = view.getMap();
        primaryMap[0].setLocationHelper(app.getLocationHelper());
        window.setContentView(view);
        window.show();
        primary[0] = window;
      });
      awaitFrames(mainOutput, 3);
      main(() -> Framework.nativeZoomToPoint(55.65, 37.63, 13, false));
      MapStyle[] styles = {MapStyle.Clear, MapStyle.Dark};
      int[][] mainReference = new int[2][], clusterReference = new int[2][];
      for (int i = 0; i < styles.length; ++i)
      {
        MapStyle style = styles[i];
        main(() -> MapStyle.set(style));
        awaitStableImage(mainOutput);
        awaitStableImage(cluster);
        mainReference[i] = mainOutput.imageColors.get().clone();
        clusterReference[i] = cluster.imageColors.get().clone();
      }
      for (boolean detach : new boolean[] {false, true})
        for (int i = 0; i < styles.length; ++i)
        {
          MapStyle style = styles[i];
          main(() -> {
            primaryMap[0].onPause();
            if (detach)
              primaryMap[0].onSurfaceDestroyed(true /* activityIsChangingConfigurations */);
            MapStyle.mark(style);
          });
          awaitStableImage(cluster);
          main(() -> {
            if (detach)
            {
              MapView view = primaryView[0];
              var holder = view.getHolder();
              primaryMap[0].onSurfaceCreated(view.getContext(), holder.getSurface(), holder.getSurfaceFrame(), 160);
              primaryMap[0].onSurfaceChanged(view.getContext(), holder.getSurface(), holder.getSurfaceFrame(), false);
            }
            primaryMap[0].onResume();
          });
          awaitStableImage(mainOutput);
          mainOutput.save(app, "theme-resumed-" + style + "-" + detach + ".png");
          assertImageEquals(mainOutput, mainReference[i]);
          assertImageEquals(cluster, clusterReference[i]);
        }
      command(app, "hide_cluster", cluster, 13);
    }
    finally
    {
      main(() -> {
        if (primary[0] != null)
          primary[0].dismiss();
        if (original[0] != null)
          MapStyle.mark(original[0]);
      });
    }
  }

  private static void assertImageEquals(Output output, int[] expected)
  {
    int[] actual = output.imageColors.get();
    assertNotNull(actual);
    int changed = 0;
    for (int i = 0; i < expected.length; ++i)
    {
      int a = expected[i], b = actual[i];
      if (Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) > 8 || Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) > 8
          || Math.abs((a & 255) - (b & 255)) > 8)
        ++changed;
    }
    assertTrue("Stale theme on display " + output.id() + ": " + changed + "/" + expected.length,
               changed < expected.length / 100);
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void mainAndClusterFrameRatesAreLimitedIndependently() throws Exception
  {
    MwmApplication app =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    org.junit.Assume.assumeTrue(Config.isAuto());
    Presentation[] primary = new Presentation[1];
    try (Output mainOutput = new Output(app, 640, 360, 160); Output first = new Output(app, 512, 288, 160);
         Output second = new Output(app, 512, 288, 160))
    {
      showUri(app, first, "16", null, "tilt=0");
      showUri(app, second, "16", null, "tilt=45&3d=1");
      awaitFrames(first, 3);
      awaitFrames(second, 3);
      main(() -> {
        app.getLocationHelper().stop();
        Presentation window = new Presentation(app, mainOutput.display.getDisplay());
        MapView view = new MapView(window.getContext());
        view.getMap().setLocationHelper(app.getLocationHelper());
        window.setContentView(view);
        window.show();
        primary[0] = window;
        ClusterTestLocation.setCoreLocation(55.7552, 37.6196, 0);
      });
      awaitFrames(mainOutput, 3);
      main(() -> Framework.nativeZoomToPoint(55.7552, 37.6196, 16, false));
      awaitTiles(awaitZoom(first.id(), 16));
      awaitTiles(awaitZoom(second.id(), 16));
      Thread.sleep(1500);
      Output[] outputs = {mainOutput, first, second};
      int[] limits = {30, 20, 20};
      long[] before = new long[outputs.length];
      for (int i = 0; i < outputs.length; ++i)
        before[i] = outputs[i].frames.get();
      long start = SystemClock.elapsedRealtime();
      for (int step = 0; SystemClock.elapsedRealtime() - start < 6000; ++step)
      {
        final int n = step;
        main(() -> {
          double lat = 55.7552 + Math.sin(n * 0.03) * 0.0002;
          Framework.nativeZoomToPoint(lat, 37.6196, 16, false);
          if (n % 10 == 0)
            ClusterTestLocation.setCoreLocation(lat, 37.6196, n % 360);
        });
        Thread.sleep(10);
      }
      long elapsed = SystemClock.elapsedRealtime() - start;
      for (int i = 0; i < outputs.length; ++i)
      {
        long frames = outputs[i].frames.get() - before[i];
        android.util.Log.i("FramePacingTest", "limit=" + limits[i] + " frames=" + frames + " wall_ms=" + elapsed);
        assertTrue("Renderer stopped for display " + outputs[i].id(), frames > 20);
        // Allow the measurement boundary and frames already queued in the compositor.
        assertTrue("Frame limit exceeded for display " + outputs[i].id(), frames <= elapsed * limits[i] / 1000 + 3);
      }
      command(app, "hide_cluster", first, 16);
      command(app, "hide_cluster", second, 16);
    }
    finally
    {
      main(() -> {
        if (primary[0] != null)
          primary[0].dismiss();
      });
    }
  }

  @Test
  public void cancellingRouteClearsEveryClusterWithoutMovingCamera() throws Exception
  {
    MwmApplication app =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    Router[] originalRouter = new Router[1];
    MapStyle[] originalStyle = new MapStyle[1];
    try (Output first = new Output(app, 768, 432, 160); Output second = new Output(app, 640, 360, 160))
    {
      showUri(app, first, "16", "0", "tilt=0&anchor=0.5,0.5");
      showUri(app, second, "16", "0", "tilt=0&anchor=0.5,0.5");
      awaitFrames(first, 3);
      awaitFrames(second, 3);
      main(() -> {
        app.getLocationHelper().stop();
        originalRouter[0] = Router.getLastUsed();
        originalStyle[0] = MapStyle.get();
        Framework.nativeCloseRouting();
        Router.set(Router.Ruler); // A real rendered route without regional map dependencies.
        ClusterTestLocation.setCoreLocation(0, 0, 0);
      });
      MapStyle[] styles = {MapStyle.VehicleDark, MapStyle.Dark};
      int[][] firstEmpty = new int[2][], secondEmpty = new int[2][];
      for (int i = 0; i < styles.length; ++i)
      {
        MapStyle style = styles[i];
        main(() -> MapStyle.set(style));
        awaitStableImage(first);
        awaitStableImage(second);
        firstEmpty[i] = first.imageColors.get().clone();
        secondEmpty[i] = second.imageColors.get().clone();
        first.save(app, "cancel-empty-" + i + ".png");
      }
      for (int attempt = 0; attempt < 4; ++attempt)
      {
        main(() -> MapStyle.set(MapStyle.VehicleDark));
        awaitStableImage(first);
        awaitStableImage(second);
        long before = first.checksum.get();
        long secondBefore = second.checksum.get();
        main(() -> {
          Framework.nativeCloseRouting();
          Framework.nativeAddRoutePoint("Start", "", RouteMarkType.Start, 0, false, 0, -0.002, false);
          Framework.nativeAddRoutePoint("Finish", "", RouteMarkType.Finish, 0, false, 0, 0.002, false);
          Framework.nativeBuildRoute();
        });
        awaitChanged(first, before);
        awaitStableImage(first);
        awaitStableImage(second);
        assertNotEquals("Route must be rendered before cancellation", before, first.checksum.get());
        assertNotEquals("Route must reach the second cluster", secondBefore, second.checksum.get());
        int[] firstRoute = first.imageColors.get().clone();
        int[] secondRoute = second.imageColors.get().clone();
        first.save(app, "cancel-route.png");
        boolean close = attempt % 2 == 0;
        main(() -> {
          if (close)
            Framework.nativeCloseRouting();
          else
            Framework.nativeRemoveRoute();
          // The Activity switches vehicle -> default style when navigation is cancelled.
          // This races the route removal queued on the resource thread.
          MapStyle.set(MapStyle.Dark);
        });
        awaitStableImage(first);
        awaitStableImage(second);
        first.save(app, "cancel-after.png");
        assertRoutePixelsCleared(first, firstEmpty[0], firstRoute, firstEmpty[1]);
        assertRoutePixelsCleared(second, secondEmpty[0], secondRoute, secondEmpty[1]);
      }
      command(app, "hide_cluster", first, 16);
      command(app, "hide_cluster", second, 16);
    }
    finally
    {
      main(() -> {
        Framework.nativeCloseRouting();
        if (originalRouter[0] != null)
          Router.set(originalRouter[0]);
        if (originalStyle[0] != null)
          MapStyle.mark(originalStyle[0]);
      });
    }
  }

  private static boolean differentColor(int a, int b)
  {
    return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) > 8 || Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) > 8
 || Math.abs((a & 255) - (b & 255)) > 8;
  }

  private static void assertRoutePixelsCleared(Output output, int[] before, int[] route, int[] after)
  {
    int changed = 0, stale = 0;
    int[] actual = output.imageColors.get();
    int width = output.reader.getWidth() / 2, height = output.reader.getHeight() / 2;
    for (int i = 0; i < before.length; ++i)
    {
      // The position marker legitimately changes appearance when routing ends. Check the
      // route on both sides of the fixed central marker, not its lighting/outline pixels.
      if (Math.abs(i % width - width / 2) < width / 16 && Math.abs(i / width - height / 2) < height / 8)
        continue;
      if (differentColor(before[i], route[i]))
      {
        ++changed;
        if (differentColor(after[i], actual[i]))
          ++stale;
      }
    }
    assertTrue("Test route must cover enough pixels", changed > 30);
    assertTrue("Stale route pixels on display " + output.id() + ": " + stale + "/" + changed, stale < changed / 20);
  }

  private static HashMap<Integer, Long> nativeCpuTicks() throws IOException
  {
    HashMap<Integer, Long> result = new HashMap<>();
    File[] tasks = new File("/proc/self/task").listFiles();
    assertNotNull(tasks);
    for (File task : tasks)
    {
      String stat;
      try
      {
        stat = new String(Files.readAllBytes(new File(task, "stat").toPath()), StandardCharsets.UTF_8);
      }
      catch (IOException ignored)
      {
        continue;
      } // A worker may exit during enumeration.
      int end = stat.lastIndexOf(')');
      if (!stat.substring(stat.indexOf('(') + 1, end).startsWith("Thread-"))
        continue;
      String[] fields = stat.substring(end + 2).trim().split("\\s+");
      result.put(Integer.parseInt(task.getName()), Long.parseLong(fields[11]) + Long.parseLong(fields[12]));
    }
    return result;
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void pausedPrimaryDoesNotSpinAndClusterRemainsIndependent() throws Exception
  {
    MwmApplication app =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    Presentation[] primary = new Presentation[1];
    MapView[] primaryMap = new MapView[1];
    try (Output mainOutput = new Output(app, 640, 360, 160); Output cluster = new Output(app, 512, 288, 160))
    {
      showUri(app, cluster, "16", null, "tilt=0");
      awaitFrames(cluster, 3);
      main(() -> {
        Presentation window = new Presentation(app, mainOutput.display.getDisplay());
        MapView view = new MapView(window.getContext());
        view.getMap().setLocationHelper(app.getLocationHelper());
        window.setContentView(view);
        window.show();
        primary[0] = window;
        primaryMap[0] = view;
      });
      awaitFrames(mainOutput, 3);
      Thread.sleep(1000);
      main(() -> primaryMap[0].getMap().onPause());
      Thread.sleep(200);
      long primaryFrames = mainOutput.frames.get(), clusterFrames = cluster.frames.get();
      var before = nativeCpuTicks();
      assertFalse("No native threads found for CPU measurement", before.isEmpty());
      long start = SystemClock.elapsedRealtime();
      for (int i = 0; i < 10; ++i)
      {
        command(app, "show_cluster", cluster, 15 + i % 2);
        Thread.sleep(200);
      }
      long elapsed = SystemClock.elapsedRealtime() - start;
      var after = nativeCpuTicks();
      long ticksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK);
      for (var entry : before.entrySet())
      {
        if (!after.containsKey(entry.getKey()))
          continue;
        long ticks = after.get(entry.getKey()) - entry.getValue();
        assertTrue("Native thread spins while primary is paused: tid=" + entry.getKey() + ", ticks=" + ticks,
                   ticks < elapsed * ticksPerSecond * 0.6 / 1000);
      }
      assertTrue("Paused primary kept presenting", mainOutput.frames.get() - primaryFrames <= 1);
      assertTrue("Pausing primary stopped the cluster", cluster.frames.get() - clusterFrames > 3);
      main(() -> primaryMap[0].getMap().onResume());
      awaitFrames(mainOutput, primaryFrames + 2);
      main(() -> primary[0].dismiss());
      primary[0] = null;
      command(app, "hide_cluster", cluster, 16);
    }
    finally
    {
      main(() -> {
        if (primary[0] != null)
        {
          primaryMap[0].getMap().onResume();
          primary[0].dismiss();
        }
      });
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void threeMapsSurviveThemeChangesAndOneClusterDisconnect() throws Exception
  {
    MwmApplication app =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    CountDownLatch initialized = new CountDownLatch(1);
    main(() -> {
      try
      {
        if (!app.initOrganicMaps(initialized::countDown))
          initialized.countDown();
      }
      catch (IOException e)
      {
        throw new AssertionError(e);
      }
    });
    assertTrue("Native initialization timed out", initialized.await(60, TimeUnit.SECONDS));
    MapStyle[] originalStyle = new MapStyle[1];
    Config.UiTheme[] originalTheme = new Config.UiTheme[1];
    Presentation[] primary = new Presentation[1];
    try (Output mainOutput = new Output(app, 640, 360, 160); Output first = new Output(app, 512, 288, 240);
         Output second = new Output(app, 720, 320, 160))
    {
      main(() -> {
        originalStyle[0] = MapStyle.get();
        originalTheme[0] = Config.UiTheme.getUiThemePreference();
        // show_cluster synchronizes the configured theme; a bare MapStyle override gets replaced.
        Config.UiTheme.setUiThemePreference(Config.UiTheme.LIGHT);
        ThemeSwitcher.INSTANCE.synchronizeApplicationTheme();
        MapStyle.mark(MapStyle.Clear);
        Presentation window = new Presentation(app, mainOutput.display.getDisplay());
        MapView view = new MapView(window.getContext());
        view.getMap().setLocationHelper(app.getLocationHelper());
        window.setContentView(view);
        window.show();
        primary[0] = window;
      });
      awaitFrames(mainOutput, 3);
      command(app, "show_cluster", first, 16);
      command(app, "show_cluster", second, 14);
      awaitFrames(first, 3);
      awaitFrames(second, 3);
      main(() -> app.getLocationHelper().stop());
      Thread.sleep(1500);
      long mainLight = mainOutput.checksum.get();
      long firstLight = first.checksum.get();
      long secondLight = second.checksum.get();
      main(() -> {
        Config.UiTheme.setUiThemePreference(Config.UiTheme.DARK);
        ThemeSwitcher.INSTANCE.synchronizeApplicationTheme();
      });
      awaitChanged(mainOutput, mainLight);
      awaitChanged(first, firstLight);
      awaitChanged(second, secondLight);
      Thread.sleep(1000);
      command(app, "hide_cluster", first, 16);
      Thread.sleep(500);
      long mainDark = mainOutput.checksum.get();
      long secondDark = second.checksum.get();
      main(() -> {
        Config.UiTheme.setUiThemePreference(Config.UiTheme.LIGHT);
        ThemeSwitcher.INSTANCE.synchronizeApplicationTheme();
      });
      awaitChanged(mainOutput, mainDark);
      awaitChanged(second, secondDark);
      command(app, "show_cluster", first, 18);
      long previous = first.frames.get();
      awaitFrames(first, previous);
      command(app, "hide_cluster", first, 18);
      command(app, "hide_cluster", second, 14);
      main(() -> primary[0].dismiss());
      primary[0] = null;
    }
    finally
    {
      main(() -> {
        if (primary[0] != null)
          primary[0].dismiss();
        if (originalTheme[0] != null)
        {
          Config.UiTheme.setUiThemePreference(originalTheme[0]);
          ThemeSwitcher.INSTANCE.synchronizeApplicationTheme();
        }
        if (originalStyle[0] != null)
          MapStyle.mark(originalStyle[0]);
      });
    }
  }
}
