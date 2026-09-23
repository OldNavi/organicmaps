package app.organicmaps.road;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.sdk.road.RoadEventKind;
import app.organicmaps.sdk.road.RoadEvents;
import app.organicmaps.settings.RoadDataSettingsFragment;
import app.organicmaps.settings.RoadEventVisibilityFragment;
import app.organicmaps.settings.SettingsActivity;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class RoadEventSettingsTest
{
  private static void main(Runnable runnable)
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
  }

  @Test
  public void twoMenusPersistSeparateMasksAndProviderHasItsOwnHeading() throws Exception
  {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    CountDownLatch ready = new CountDownLatch(1);
    main(() -> {
      try
      {
        if (!MwmApplication.from(context).initOrganicMaps(ready::countDown))
          ready.countDown();
      }
      catch (Exception e)
      {
        throw new AssertionError(e);
      }
    });
    assertTrue(ready.await(30, TimeUnit.SECONDS));
    var prefs = MwmApplication.prefs(context);
    int oldMap = RoadEventVisibility.get(prefs, false);
    int oldRoute = RoadEventVisibility.get(prefs, true);
    boolean oldEnabled = prefs.getBoolean(RoadDataManager.ENABLED, false);
    AtomicReference<SettingsActivity> activity = new AtomicReference<>();
    try
    {
      main(() -> {
        prefs.edit().putBoolean(RoadDataManager.ENABLED, true).apply();
        RoadDataManager.get(context).configure();
      });
      context.startActivity(new Intent(context, SettingsActivity.class)
                                .putExtra("open_road_events", true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long deadline = SystemClock.elapsedRealtime() + 10000;
      while (activity.get() == null && SystemClock.elapsedRealtime() < deadline)
      {
        main(() -> {
          for (Activity current : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (current instanceof SettingsActivity settings)
              activity.set(settings);
        });
        Thread.sleep(100);
      }
      assertNotNull(activity.get());
      AtomicReference<RoadDataSettingsFragment> root = new AtomicReference<>();
      main(() -> {
        for (var fragment : activity.get().getSupportFragmentManager().getFragments())
          if (fragment instanceof RoadDataSettingsFragment settings)
            root.set(settings);
        assertNotNull(root.get());
        var screen = root.get().getPreferenceScreen();
        assertEquals(context.getString(R.string.road_events_show_map), screen.getPreference(1).getTitle());
        assertEquals(context.getString(R.string.road_events_show_route), screen.getPreference(2).getTitle());
        var providers = (PreferenceCategory) screen.getPreference(3);
        assertEquals(context.getString(R.string.road_events_providers), providers.getTitle());
        assertEquals(1, providers.getPreferenceCount());
      });
      for (boolean route : new boolean[] {false, true})
      {
        main(()
                 -> activity.get().onPreferenceStartFragment(
                     root.get(), root.get().getPreferenceScreen().getPreference(route ? 2 : 1)));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        main(() -> {
          RoadEventVisibilityFragment editor = null;
          for (var fragment : activity.get().getSupportFragmentManager().getFragments())
            if (fragment instanceof RoadEventVisibilityFragment visible && visible.isResumed())
              editor = visible;
          assertNotNull(editor);
          var screen = editor.getPreferenceScreen();
          SwitchPreferenceCompat bump = null;
          for (int i = 0; i < screen.getPreferenceCount(); ++i)
            if (context.getString(R.string.road_event_bump).contentEquals(screen.getPreference(i).getTitle()))
              bump = (SwitchPreferenceCompat) screen.getPreference(i);
          assertNotNull(bump);
          int other = RoadEventVisibility.get(prefs, !route);
          boolean enabled = !bump.isChecked();
          assertTrue(bump.callChangeListener(enabled));
          bump.setChecked(enabled);
          assertEquals(enabled, (RoadEventVisibility.get(prefs, route) & (1 << RoadEventKind.BUMP)) != 0);
          assertEquals(other, RoadEventVisibility.get(prefs, !route));
          activity.get().getSupportFragmentManager().popBackStackImmediate();
        });
      }
      main(() -> {
        long records = RoadEvents.nativeGetState()[1];
        prefs.edit().putBoolean(RoadDataManager.ENABLED, false).apply();
        RoadDataManager.get(context).configure();
        assertEquals(0, RoadEvents.nativeGetState()[0]);
        assertEquals(records, RoadEvents.nativeGetState()[1]);
        prefs.edit().putBoolean(RoadDataManager.ENABLED, true).apply();
        RoadDataManager.get(context).configure();
        assertEquals(1, RoadEvents.nativeGetState()[0]);
        assertEquals(records, RoadEvents.nativeGetState()[1]);
      });
    }
    finally
    {
      main(() -> {
        prefs.edit()
            .putInt(RoadEventVisibility.MAP, oldMap)
            .putInt(RoadEventVisibility.ROUTE, oldRoute)
            .putBoolean(RoadDataManager.ENABLED, oldEnabled)
            .apply();
        RoadDataManager.get(context).configure();
        if (activity.get() != null)
          activity.get().finish();
      });
    }
  }
}
