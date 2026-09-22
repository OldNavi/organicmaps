package app.organicmaps.widget;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.BuildConfig;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.routing.SpeedWarningController;
import app.organicmaps.sdk.widgets.speedlimit.SpeedLimitView;
import app.organicmaps.settings.SpeedWarningSettings;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.Test;

public class MapSpeedViewTest
{
  @Test
  public void offsetWarningVisualExamples()
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
      var prefs = MwmApplication.prefs(base);
      boolean hadOffset = prefs.contains(SpeedWarningSettings.OFFSET_KEY);
      int oldOffset = prefs.getInt(SpeedWarningSettings.OFFSET_KEY, 0);
      prefs.edit().putInt(SpeedWarningSettings.OFFSET_KEY, 16).apply();
      try
      {
        for (int night : new int[] {Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES})
        {
          Configuration config = new Configuration(base.getResources().getConfiguration());
          config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
          Context context = new ContextThemeWrapper(base.createConfigurationContext(config), R.style.MwmTheme);
          Bitmap bitmap = Bitmap.createBitmap(700, 260, Bitmap.Config.ARGB_8888);
          Canvas canvas = new Canvas(bitmap);
          canvas.drawColor(night == Configuration.UI_MODE_NIGHT_NO ? 0xffeceee8 : 0xff141a26);
          Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
          text.setColor(night == Configuration.UI_MODE_NIGHT_NO ? Color.BLACK : Color.WHITE);
          text.setTextSize(24);
          canvas.drawText("Лимит 60 км/ч · Оффсет +16 км/ч", 24, 35, text);
          text.setTextSize(20);
          for (int i = 0; i < 2; ++i)
          {
            int value = 76 + i;
            boolean exceeded = SpeedWarningController.isExceeded(context, value / 3.6, 60 / 3.6);
            assertEquals(i == 1, exceeded);
            MapSpeedView row = new MapSpeedView(context, null);
            row.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
            row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
            SpeedLimitView speed = row.findViewById(R.id.map_speed_value);
            SpeedLimitView limit = row.findViewById(R.id.map_speed_limit);
            speed.setSpeedLimit(value, exceeded);
            limit.setSpeedLimit(60, false);
            assertEquals(exceeded, speed.isAlert());
            assertFalse(limit.isAlert());
            int x = 24 + i * 350;
            canvas.drawText(value + " км/ч — " + (exceeded ? "предупреждение" : "без подсветки"), x, 78, text);
            int saved = canvas.save();
            canvas.translate(x, 100);
            float scale = 240f / row.getWidth();
            canvas.scale(scale, scale);
            row.draw(canvas);
            canvas.restoreToCount(saved);
          }
          save(context, bitmap, "speed-offset-preview-" + night + ".png");
          bitmap.recycle();
        }
      }
      finally
      {
        var editor = prefs.edit();
        if (hadOffset)
          editor.putInt(SpeedWarningSettings.OFFSET_KEY, oldOffset);
        else
          editor.remove(SpeedWarningSettings.OFFSET_KEY);
        editor.apply();
      }
    });
  }

  @Test
  public void numbersKeepTheirSizeAndFitThreeDigits()
  {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    instrumentation.runOnMainSync(() -> {
      Context base = instrumentation.getTargetContext();
      for (int night : new int[] {Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES})
        for (float fontScale : new float[] {1, 1.5f})
        {
          Configuration config = new Configuration(base.getResources().getConfiguration());
          config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
          config.fontScale = fontScale;
          Context context = new ContextThemeWrapper(base.createConfigurationContext(config), R.style.MwmTheme);
          MapSpeedView row = new MapSpeedView(context, null);
          row.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST),
                      View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
          row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
          SpeedLimitView speed = row.findViewById(R.id.map_speed_value);
          SpeedLimitView limit = row.findViewById(R.id.map_speed_limit);
          int previousHeight = 0;
          for (int value : new int[] {0, 60, 120, 200})
          {
            speed.setSpeedLimit(value, false);
            limit.setSpeedLimit(value == 0 ? 60 : value, false);
            Bitmap circle = Bitmap.createBitmap(speed.getWidth(), speed.getHeight(), Bitmap.Config.ARGB_8888);
            speed.draw(new Canvas(circle));
            Rect text = new Rect();
            int inset = speed.getWidth() / 8;
            for (int y = inset; y < circle.getHeight() - inset; ++y)
              for (int x = inset; x < circle.getWidth() - inset; ++x)
              {
                int pixel = circle.getPixel(x, y);
                boolean ink = night == Configuration.UI_MODE_NIGHT_NO
                                ? Color.red(pixel) < 80 && Color.green(pixel) < 80 && Color.blue(pixel) < 80
                                : Color.red(pixel) > 200 && Color.green(pixel) > 200 && Color.blue(pixel) > 200;
                if (Color.alpha(pixel) > 200 && ink)
                  text.union(x, y, x + 1, y + 1);
              }
            assertFalse("Missing speed digits", text.isEmpty());
            assertTrue("Digits are too tall", text.height() <= circle.getHeight() * 0.4f);
            assertTrue("Three digits must leave space before the rim", text.width() <= circle.getWidth() * 0.7f);
            if (previousHeight != 0)
              assertTrue("Font size changes with the number of digits", Math.abs(text.height() - previousHeight) <= 2);
            previousHeight = text.height();
            circle.recycle();
            Bitmap bitmap = Bitmap.createBitmap(row.getWidth(), row.getHeight(), Bitmap.Config.ARGB_8888);
            row.draw(new Canvas(bitmap));
            save(context, bitmap, "speed-digits-" + night + "-" + fontScale + "-" + value + ".png");
            bitmap.recycle();
          }
        }
    });
  }

  @Test
  public void rightEdgeMatchesZoomOnCompactAndWideScreens()
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
      for (int[] size : new int[][] {{360, 800}, {800, 360}, {1440, 810}})
      {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.screenWidthDp = size[0];
        config.screenHeightDp = size[1];
        config.smallestScreenWidthDp = Math.min(size[0], size[1]);
        config.orientation =
            size[0] > size[1] ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        Context context = new ContextThemeWrapper(base.createConfigurationContext(config), R.style.MwmTheme);
        ViewGroup root =
            (ViewGroup) LayoutInflater.from(context).inflate(R.layout.map_buttons_layout_regular, null, false);
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(size[0] * density), height = Math.round(size[1] * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        View zoom = root.findViewById(R.id.nav_zoom_out);
        Rect zoomBounds = new Rect();
        zoom.getDrawingRect(zoomBounds);
        root.offsetDescendantRectToMyCoords(zoom, zoomBounds);
        assertEquals("Speed/zoom right edges on " + size[0] + "x" + size[1], zoomBounds.right,
                     root.findViewById(R.id.map_speed).getRight());
      }
    });
  }

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
            int border = context.getColor(R.color.map_current_speed_border);
            number.setSpeedLimit(120, true);
            bitmap.eraseColor(Color.TRANSPARENT);
            speed.draw(new Canvas(bitmap));
            int alert = bitmap.getPixel(number.getWidth() / 2, number.getHeight() / 5);
            assertTrue(Color.red(alert) > Color.green(alert) * 2);
            assertEquals("The speed-limit sign must stay white", Color.WHITE,
                         bitmap.getPixel(limit.getLeft() + limit.getWidth() / 2, limit.getHeight() / 5));
            int alertBorder = bitmap.getPixel(number.getWidth() / 2, 0);
            // A one-pixel rim is antialiased over a different fill. Compare it to the configured rim,
            // rather than requiring the same blended edge pixel before and after an alert.
            assertTrue("Current-speed border must survive the alert: " + Integer.toHexString(alertBorder),
                       colorDistance(alertBorder, border) < colorDistance(alert, border) / 2);
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
  private static int colorDistance(int a, int b)
  {
    return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b))
  + Math.abs(Color.blue(a) - Color.blue(b));
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
