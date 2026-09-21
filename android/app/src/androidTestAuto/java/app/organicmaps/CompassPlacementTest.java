package app.organicmaps;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import app.organicmaps.sdk.Map;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class CompassPlacementTest
{
  @Test
  public void compassHasSpaceAboveGpsAfterMapRotation() throws Exception
  {
    assumeTrue("auto".equals(BuildConfig.FLAVOR));
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var context = instrumentation.getTargetContext();
    context.startActivity(new Intent(context, SplashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    AtomicReference<MwmActivity> activity = new AtomicReference<>();
    long deadline = SystemClock.elapsedRealtime() + 20000;
    while (activity.get() == null && SystemClock.elapsedRealtime() < deadline)
    {
      instrumentation.runOnMainSync(() -> {
        for (Activity resumed : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
          if (resumed instanceof MwmActivity map)
            activity.set(map);
      });
      Thread.sleep(100);
    }
    assertNotNull(activity.get());
    Thread.sleep(1000);
    float[] center = new float[2];
    instrumentation.runOnMainSync(() -> {
      View map = activity.get().findViewById(R.id.map);
      center[0] = map.getWidth() / 2.0f;
      center[1] = map.getHeight() / 2.0f;
      View gps = activity.get().findViewById(R.id.my_position);
      View zoom = activity.get().findViewById(R.id.zoom_buttons_container);
      int[] gpsLocation = new int[2];
      int[] zoomLocation = new int[2];
      gps.getLocationInWindow(gpsLocation);
      zoom.getLocationInWindow(zoomLocation);
      assertTrue(gpsLocation[1] - zoomLocation[1] - zoom.getHeight() >= gps.getHeight());
    });
    long start = SystemClock.uptimeMillis();
    for (int step = 0; step <= 24; ++step)
    {
      int index = step;
      instrumentation.runOnMainSync(()
                                        -> rotate(center[0], center[1], index * Math.PI / 72, start,
                                                  index == 0 ? Map.NATIVE_ACTION_DOWN : Map.NATIVE_ACTION_MOVE));
      Thread.sleep(30);
    }
    instrumentation.runOnMainSync(() -> rotate(center[0], center[1], Math.PI / 3, start, Map.NATIVE_ACTION_UP));
    Thread.sleep(1000);
    Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
    assertNotNull(screenshot);
    try (FileOutputStream output = new FileOutputStream(new File(context.getCacheDir(), "compass-near-gps.png")))
    {
      assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
    }
    screenshot.recycle();
  }

  private static void rotate(float x, float y, double angle, long start, int action)
  {
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
    MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
    for (int i = 0; i < 2; ++i)
    {
      properties[i] = new MotionEvent.PointerProperties();
      properties[i].id = i;
      properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coords[i] = new MotionEvent.PointerCoords();
      coords[i].x = x + (i == 0 ? -1 : 1) * 180 * (float) Math.cos(angle);
      coords[i].y = y + (i == 0 ? -1 : 1) * 180 * (float) Math.sin(angle);
      coords[i].pressure = 1;
      coords[i].size = 1;
    }
    MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 2, properties,
                                           coords, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    try
    {
      Map.onTouch(action, event, Map.INVALID_POINTER_MASK);
    }
    finally
    {
      event.recycle();
    }
  }
}
