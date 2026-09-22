package app.organicmaps.widget;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.CarDirection;
import app.organicmaps.sdk.routing.LaneInfo;
import app.organicmaps.sdk.routing.LaneWay;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.Test;

public class AutoNavigationAppearanceTest
{
  private static LaneInfo[] lanes()
  {
    return new LaneInfo[] {new LaneInfo(new LaneWay[] {LaneWay.Left, LaneWay.Through}, LaneWay.Through),
                           new LaneInfo(new LaneWay[] {LaneWay.Through}, LaneWay.Through),
                           new LaneInfo(new LaneWay[] {LaneWay.Through, LaneWay.Right}, LaneWay.None),
                           new LaneInfo(new LaneWay[] {LaneWay.MergeToRight}, LaneWay.None)};
  }

  @Test
  public void allBranchesAreVisibleAndRecommendedBranchIsOnTop()
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
      int active = Color.WHITE, inactive = Color.GRAY;
      AutoLanesDrawable drawable = new AutoLanesDrawable(
          context, new LaneInfo[] {new LaneInfo(new LaneWay[] {LaneWay.Left, LaneWay.Through}, LaneWay.Through)},
          active, inactive);
      drawable.setBounds(0, 0, 360, 400);
      Bitmap bitmap = Bitmap.createBitmap(360, 400, Bitmap.Config.ARGB_8888);
      drawable.draw(new Canvas(bitmap));
      int inactivePixels = 0, activePixels = 0;
      for (int y = 0; y < bitmap.getHeight(); ++y)
        for (int x = 0; x < bitmap.getWidth(); ++x)
        {
          int pixel = bitmap.getPixel(x, y);
          if (pixel == inactive)
            ++inactivePixels;
          if (pixel == active)
            ++activePixels;
        }
      assertTrue("Missing secondary branch", inactivePixels > 100);
      assertTrue("Missing recommended arrow", activePixels > 100);
      assertEquals("Inactive branch covered the common stem", active, bitmap.getPixel(180, 330));
      Rect bounds = new Rect(drawable.getBounds());
      for (int i = 0; i < 100; ++i)
        drawable.setBounds(0, 0, 360, 400);
      assertEquals("Repeated layouts must not shrink the arrows", bounds, drawable.getBounds());
      bitmap.recycle();
    });
  }

  @Test
  public void autoLayoutAndManeuverResourcesWorkInBothThemesAndOrientations()
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
      for (int night : new int[] {Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES})
        for (int[] size : new int[][] {{360, 800}, {800, 360}})
        {
          Configuration config = new Configuration(base.getResources().getConfiguration());
          config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
          config.screenWidthDp = size[0];
          config.screenHeightDp = size[1];
          config.smallestScreenWidthDp = Math.min(size[0], size[1]);
          config.orientation =
              size[0] > size[1] ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
          Context context = new ContextThemeWrapper(base.createConfigurationContext(config), R.style.MwmTheme);
          ViewGroup root = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.layout_nav_top, null, false);
          View rawLanes = root.findViewById(R.id.lanes);
          assertTrue("Auto layout must use the automotive renderer", rawLanes instanceof AutoLanesView);
          AutoLanesView laneView = (AutoLanesView) rawLanes;
          laneView.setLanes(lanes());
          assertEquals(View.VISIBLE, laneView.getVisibility());
          View turnFrame = root.findViewById(R.id.nav_next_turn_frame);
          ImageView turn = turnFrame.findViewById(R.id.turn);
          TextView distance = turnFrame.findViewById(R.id.distance);
          distance.setText("250 m");
          ((TextView) root.findViewById(R.id.street)).setText("M-4 · Sample street");
          float density = context.getResources().getDisplayMetrics().density;
          int width = Math.round(size[0] * density), height = Math.round(size[1] * density);
          root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                       View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
          root.layout(0, 0, width, height);
          assertTrue(laneView.getWidth() > 0);
          assertTrue(laneView.getLeft() >= 0 && laneView.getRight() <= width);
          for (CarDirection direction : CarDirection.values())
          {
            Drawable icon = AppCompatResources.getDrawable(context, direction.getTurnRes(2));
            assertNotNull(icon);
            assertEquals("Density-qualified stock bitmap shadowed the auto vector: " + direction,
                         Math.round(32 * density), icon.getIntrinsicWidth());
          }
          for (int exit = 1; exit <= 12; ++exit)
            assertNotNull(AppCompatResources.getDrawable(context, CarDirection.LeaveRoundAbout.getTurnRes(exit)));
          for (CarDirection direction : new CarDirection[] {CarDirection.TurnLeft, CarDirection.LeaveRoundAbout})
          {
            turn.setImageResource(direction.getTurnRes(2));
            Bitmap arrow = Bitmap.createBitmap(turn.getWidth(), turn.getHeight(), Bitmap.Config.ARGB_8888);
            turn.draw(new Canvas(arrow));
            int foreground = context.getColor(R.color.auto_nav_foreground);
            int pixels = 0;
            for (int y = 0; y < arrow.getHeight(); ++y)
              for (int x = 0; x < arrow.getWidth(); ++x)
                if (arrow.getPixel(x, y) == foreground)
                  ++pixels;
            assertTrue("Maneuver arrow must contrast with its day/night panel", pixels > 20);
            arrow.recycle();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(night == Configuration.UI_MODE_NIGHT_NO ? 0xffeceee8 : 0xff141a26);
            root.draw(canvas);
            save(context, bitmap, "navigation-" + night + "-" + size[0] + "-" + direction + ".png");
            bitmap.recycle();
          }
          laneView.setLanes(lanes());
          assertEquals(View.VISIBLE, laneView.getVisibility());
          laneView.setLanes(null);
          assertEquals(View.GONE, laneView.getVisibility());
          laneView.setLanes(new LaneInfo[0]);
          assertEquals(View.GONE, laneView.getVisibility());
          laneView.setLanes(lanes());
          assertEquals(View.VISIBLE, laneView.getVisibility());
        }
    });
  }

  private static void save(Context context, Bitmap bitmap, String name)
  {
    File directory = new File(context.getCacheDir(), "navigation-appearance-tests");
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
