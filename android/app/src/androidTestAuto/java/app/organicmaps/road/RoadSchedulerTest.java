package app.organicmaps.road;

import static org.junit.Assert.*;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.NetworkType;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import app.organicmaps.MwmApplication;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class RoadSchedulerTest
{
  @Test
  public void periodicWorkIsUniqueNetworkConstrainedAndCancelledWhenDisabled() throws Exception
  {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var prefs = MwmApplication.prefs(context);
    var work = WorkManager.getInstance(context);
    String provider = "schedule-test-" + UUID.randomUUID();
    String name = "road-update:" + provider + ":periodic";
    try
    {
      prefs.edit().putBoolean(RoadUpdateSettings.enabledKey(provider), true).apply();
      // This synthetic provider is rejected by the real worker before any network/authentication work.
      RoadUpdateScheduler.sync(context, provider, true);
      var first = work.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS);
      assertEquals(1, first.size());
      assertEquals(NetworkType.CONNECTED, first.get(0).getConstraints().getRequiredNetworkType());
      RoadUpdateScheduler.sync(context, provider, true);
      var second = work.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS);
      assertEquals(1, second.size());
      assertEquals(first.get(0).getId(), second.get(0).getId());
      prefs.edit().putBoolean(RoadUpdateSettings.enabledKey(provider), false).apply();
      RoadUpdateScheduler.sync(context, provider, true);
      var cancelled = work.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS);
      assertEquals(WorkInfo.State.CANCELLED, cancelled.get(0).getState());
    }
    finally
    {
      work.cancelAllWorkByTag("road-update:" + provider).getResult().get(5, TimeUnit.SECONDS);
      prefs.edit().remove(RoadUpdateSettings.enabledKey(provider)).apply();
    }
  }
}
