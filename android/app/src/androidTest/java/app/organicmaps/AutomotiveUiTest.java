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
import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.util.ThemeSwitcher;
import app.organicmaps.util.ThemeUtils;
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
    awaitMap();
    Config.UiTheme original = Config.UiTheme.getUiThemePreference();
    try
    {
      for (Config.UiTheme theme :
           new Config.UiTheme[] {Config.UiTheme.LIGHT, Config.UiTheme.DARK, Config.UiTheme.LIGHT})
      {
        main(() -> {
          Config.UiTheme.setUiThemePreference(theme);
          ThemeSwitcher.INSTANCE.synchronizeApplicationTheme();
        });
        Thread.sleep(1000);
        awaitMap();
        main(() -> context.startActivity(new Intent(search)));
        Thread.sleep(1500);
        MwmActivity map = awaitMap();
        main(() -> {
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
    }
    finally
    {
      main(() -> {
        Config.UiTheme.setUiThemePreference(original);
        ThemeSwitcher.INSTANCE.synchronizeApplicationTheme();
      });
    }
  }
}
