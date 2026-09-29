package app.organicmaps;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

import android.app.Activity;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import app.organicmaps.sdk.MapView;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.util.ThemeSwitcher;
import app.organicmaps.util.ThemeUtils;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class AutomotiveUiTest
{
  private static void main(Runnable action)
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
  }

  private static MwmActivity awaitMap() throws InterruptedException
  {
    AtomicReference<MwmActivity> result = new AtomicReference<>();
    long deadline = SystemClock.elapsedRealtime() + 30000;
    while (SystemClock.elapsedRealtime() < deadline)
    {
      main(() -> {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
          if (activity instanceof MwmActivity map)
            result.set(map);
      });
      if (result.get() != null)
        return result.get();
      Thread.sleep(100);
    }
    throw new AssertionError("Map activity did not resume");
  }

  private static void awaitSearchResults(MwmActivity map) throws InterruptedException
  {
    AtomicBoolean ready = new AtomicBoolean();
    long deadline = SystemClock.elapsedRealtime() + 30000;
    while (!ready.get() && SystemClock.elapsedRealtime() < deadline)
    {
      main(() -> {
        View frame = map.findViewById(R.id.results_frame);
        RecyclerView results = frame == null ? null : frame.findViewById(R.id.recycler);
        ready.set(results != null && results.getChildCount() > 0);
      });
      Thread.sleep(100);
    }
    assertTrue("Search must contain actual results", ready.get());
  }

  @Test
  public void automotiveStartupAndSearchThemeTransitions() throws Exception
  {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assumeFalse(context.getResources().getBoolean(R.bool.show_startup_splash));
    var startup = new ContextThemeWrapper(context, R.style.MwmTheme_Splash);
    TypedArray attrs = startup.obtainStyledAttributes(
        new int[] {android.R.attr.windowIsTranslucent, android.R.attr.windowDisablePreview});
    try
    {
      assertTrue(attrs.getBoolean(0, false));
      assertTrue(attrs.getBoolean(1, false));
    }
    finally
    {
      attrs.recycle();
    }
    Intent search = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=Moscow"), context, SplashActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    main(() -> context.startActivity(search));
    MwmActivity originalMap = awaitMap();
    awaitSearchResults(originalMap);
    MapView surfaceView = originalMap.findViewById(R.id.map);
    AtomicInteger surfaceDestructions = new AtomicInteger();
    SurfaceHolder.Callback callback = new SurfaceHolder.Callback() {
      @Override
      public void surfaceCreated(SurfaceHolder holder)
      {}
      @Override
      public void surfaceChanged(SurfaceHolder holder, int format, int width, int height)
      {}
      @Override
      public void surfaceDestroyed(SurfaceHolder holder)
      {
        surfaceDestructions.incrementAndGet();
      }
    };
    main(() -> surfaceView.getHolder().addCallback(callback));
    Config.UiTheme original = Config.UiTheme.getUiThemePreference();
    boolean originalAutoDark = Config.UiTheme.isAutoDarkNavigationEnabled();
    try
    {
      for (Config.UiTheme theme :
           new Config.UiTheme[] {Config.UiTheme.LIGHT, Config.UiTheme.DARK, Config.UiTheme.LIGHT})
      {
        main(() -> {
          Config.UiTheme.setUiThemePreference(theme);
          ThemeSwitcher.INSTANCE.synchronizeApplicationTheme();
        });
        Thread.sleep(1500);
        MwmActivity map = awaitMap();
        awaitSearchResults(map);
        main(() -> {
          assertSame("Theme change must preserve the activity", originalMap, map);
          assertSame(surfaceView, map.findViewById(R.id.map));
          assertEquals("Map surface was destroyed during a theme change", 0, surfaceDestructions.get());
          assertTrue(surfaceView.getHolder().getSurface().isValid());
          assertEquals(theme == Config.UiTheme.DARK, ThemeUtils.isDarkTheme(map));
          View frame = map.findViewById(R.id.results_frame);
          assertNotNull(frame);
          assertEquals(ThemeUtils.getColor(map, R.attr.windowBackgroundForced),
                       ((ColorDrawable) frame.getBackground()).getColor());
          RecyclerView results = frame.findViewById(R.id.recycler);
          assertTrue("Search must contain actual results", results.getChildCount() > 0);
          for (int i = 0; i < results.getChildCount(); ++i)
          {
            TextView title = results.getChildAt(i).findViewById(R.id.title);
            if (title != null)
              assertEquals(ThemeUtils.getColor(map, android.R.attr.textColorPrimary), title.getCurrentTextColor());
          }
        });
      }

      main(() -> {
        Config.UiTheme.setAutoDarkNavigationEnabled(false);
        RoutingController.get().prepare(
            MapObject.createMapObject(MapObject.POI, "Start", "", 55.7526579975, 37.5813869952),
            MapObject.createMapObject(MapObject.POI, "Finish", "", 55.7526504502, 37.5784285186), Router.Vehicle);
      });
      AtomicBoolean navigating = new AtomicBoolean();
      long deadline = SystemClock.elapsedRealtime() + 30000;
      while (!navigating.get() && SystemClock.elapsedRealtime() < deadline)
      {
        main(() -> {
          if (RoutingController.get().isBuilt() && !RoutingController.get().isNavigating())
            RoutingController.get().start();
          navigating.set(RoutingController.get().isNavigating());
        });
        Thread.sleep(100);
      }
      assertTrue("Route did not start; this test needs the Moscow road fixture", navigating.get());
      for (Config.UiTheme theme : new Config.UiTheme[] {Config.UiTheme.DARK, Config.UiTheme.LIGHT})
      {
        main(() -> {
          Config.UiTheme.setUiThemePreference(theme);
          ThemeSwitcher.INSTANCE.synchronizeApplicationTheme();
        });
        Thread.sleep(1500);
        MwmActivity map = awaitMap();
        main(() -> {
          assertSame(originalMap, map);
          assertSame(surfaceView, map.findViewById(R.id.map));
          assertEquals(0, surfaceDestructions.get());
          assertTrue(surfaceView.getHolder().getSurface().isValid());
          assertTrue(RoutingController.get().isNavigating());
          assertEquals(View.VISIBLE, map.findViewById(R.id.navigation_frame).getVisibility());
          assertEquals(theme == Config.UiTheme.DARK, ThemeUtils.isDarkTheme(map));
        });
      }
    }
    finally
    {
      main(() -> {
        RoutingController.get().cancel();
        Config.UiTheme.setAutoDarkNavigationEnabled(originalAutoDark);
        surfaceView.getHolder().removeCallback(callback);
        Config.UiTheme.setUiThemePreference(original);
        ThemeSwitcher.INSTANCE.synchronizeApplicationTheme();
      });
    }
  }
}
