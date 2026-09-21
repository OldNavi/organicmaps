package app.organicmaps.widget;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.BuildConfig;
import app.organicmaps.R;
import app.organicmaps.sdk.widgets.speedlimit.SpeedLimitView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.Test;

public class MapSpeedViewTest
{
  @Test
  public void circlesRemainReadableInBothThemesAndClearTheTurnPanel()
  {
    assumeTrue("auto".equals(BuildConfig.FLAVOR));
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    instrumentation.runOnMainSync(() -> {
      Context base = instrumentation.getTargetContext();
      for (int night : new int[] {Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES})
      {
        for (int orientation : new int[] {Configuration.ORIENTATION_PORTRAIT, Configuration.ORIENTATION_LANDSCAPE})
        {
          Configuration config = new Configuration(base.getResources().getConfiguration());
          config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
          config.orientation = orientation;
          config.screenWidthDp = orientation == Configuration.ORIENTATION_PORTRAIT ? 360 : 800;
          config.screenHeightDp = orientation == Configuration.ORIENTATION_PORTRAIT ? 800 : 360;
          config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp);
          Context context = new ContextThemeWrapper(base.createConfigurationContext(config), R.style.MwmTheme);
          for (int layout : new int[] {R.layout.layout_nav_top, R.layout.map_buttons_layout_regular})
          {
            View root = LayoutInflater.from(context).inflate(layout, null, false);
            View speed = root.findViewById(layout == R.layout.layout_nav_top ? R.id.nav_speed_limit : R.id.map_speed);
            SpeedLimitView number = speed.findViewById(R.id.map_speed_value);
            number.setSpeedLimit(120, false);
            SpeedLimitView limit = speed.findViewById(R.id.map_speed_limit);
            assertEquals(View.VISIBLE, limit.getVisibility());
            limit.setSpeedLimit(100, false);
            float density = context.getResources().getDisplayMetrics().density;
            int width = (int) (config.screenWidthDp * density);
            int height = (int) (config.screenHeightDp * density);
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                         View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
            assertTrue(speed.getLeft() >= 0 && speed.getRight() <= width);
            assertEquals(limit.getWidth(), limit.getHeight());
            View currentCircle = number;
            assertEquals(Math.round(8 * density), currentCircle.getRight() - limit.getLeft());
            assertTrue(limit.getZ() > currentCircle.getZ());
            if (layout == R.layout.layout_nav_top)
              assertTrue("Speed row overlaps turn panel: " + speed.getLeft() + " < "
                             + root.findViewById(R.id.nav_next_turn_container).getRight(),
                         speed.getLeft() >= root.findViewById(R.id.nav_next_turn_container).getRight());
            Bitmap bitmap = Bitmap.createBitmap(speed.getWidth(), speed.getHeight(), Bitmap.Config.ARGB_8888);
            speed.draw(new Canvas(bitmap));
            save(context, bitmap, "speed-" + night + "-" + orientation + "-" + layout + ".png");
            // The speed circle uses the app theme; the road sign stays white in both themes.
            int background = bitmap.getPixel(limit.getWidth() / 2, limit.getHeight() / 8);
            boolean light = Color.red(background) + Color.green(background) + Color.blue(background) > 384;
            assertEquals(night == Configuration.UI_MODE_NIGHT_NO, light);
            assertEquals(Color.WHITE, bitmap.getPixel(limit.getLeft() + limit.getWidth() / 2, limit.getHeight() / 5));
            limit.setSpeedLimit(100, true);
            speed.draw(new Canvas(bitmap));
            int alert = bitmap.getPixel(limit.getLeft() + limit.getWidth() / 2, limit.getHeight() / 5);
            assertTrue(Color.red(alert) > Color.green(alert) * 2);
            save(context, bitmap, "speed-alert-" + night + "-" + orientation + "-" + layout + ".png");
            number.setSpeedLimit(-1, false);
            limit.setSpeedLimit(0, true);
            assertFalse(limit.isAlert());
            speed.draw(new Canvas(bitmap));
            int unknown = bitmap.getPixel(limit.getLeft() + limit.getWidth() * 3 / 8, limit.getHeight() / 2);
            assertTrue("Unknown limit must draw a dark dash",
                       Color.red(unknown) < 80 && Color.green(unknown) < 80 && Color.blue(unknown) < 80);
            assertEquals(View.VISIBLE, limit.getVisibility());
            assertEquals("Unknown marker must have a gap in the middle", Color.WHITE,
                         bitmap.getPixel(limit.getLeft() + limit.getWidth() / 2, limit.getHeight() / 2));
            // Both signs use exactly the same text geometry, including the unknown marker.
            for (int y = limit.getHeight() / 3; y < limit.getHeight() * 2 / 3; ++y)
              for (int x = limit.getWidth() / 3; x < limit.getWidth() * 2 / 3; ++x)
              {
                int left = bitmap.getPixel(x, y);
                int right = bitmap.getPixel(limit.getLeft() + x, y);
                boolean leftDash =
                    night == Configuration.UI_MODE_NIGHT_NO ? Color.red(left) < 80 : Color.red(left) > 200;
                assertEquals(leftDash, Color.red(right) < 80);
              }
            save(context, bitmap, "speed-unknown-" + night + "-" + orientation + "-" + layout + ".png");
            bitmap.recycle();
          }
        }
      }
    });
  }
  private static void save(Context context, Bitmap bitmap, String name)
  {
    File directory = new File(context.getCacheDir(), "speed-view-tests");
    assertTrue(directory.isDirectory() || directory.mkdirs());
    try (FileOutputStream output = new FileOutputStream(new File(directory, name)))
    {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
    }
    catch (IOException e)
    {
      throw new AssertionError(e);
    }
  }
}
