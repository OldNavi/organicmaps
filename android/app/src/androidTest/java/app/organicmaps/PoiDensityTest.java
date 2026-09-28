package app.organicmaps;

import static org.junit.Assert.*;

import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.sdk.rendering.PoiDensity;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class PoiDensityTest
{
  @Test
  public void displaysKeepIndependentSettings() throws Exception
  {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var app = (MwmApplication) instrumentation.getTargetContext().getApplicationContext();
    CountDownLatch ready = new CountDownLatch(1);
    instrumentation.runOnMainSync(() -> {
      try
      {
        if (!app.initOrganicMaps(ready::countDown))
          ready.countDown();
      }
      catch (IOException e)
      {
        throw new AssertionError(e);
      }
    });
    assertTrue(ready.await(60, TimeUnit.SECONDS));
    instrumentation.runOnMainSync(() -> {
      int main = PoiDensity.nativeGet(false);
      int cluster = PoiDensity.nativeGet(true);
      try
      {
        for (int density : new int[] {PoiDensity.LOW, PoiDensity.NORMAL, PoiDensity.HIGH})
        {
          PoiDensity.nativeSet(false, density);
          assertEquals(density, PoiDensity.nativeGet(false));
          assertEquals(cluster, PoiDensity.nativeGet(true));
        }
        for (int density : new int[] {PoiDensity.LOW, PoiDensity.NORMAL, PoiDensity.HIGH})
        {
          PoiDensity.nativeSet(true, density);
          assertEquals(density, PoiDensity.nativeGet(true));
          assertEquals(PoiDensity.HIGH, PoiDensity.nativeGet(false));
        }
      }
      finally
      {
        PoiDensity.nativeSet(false, main);
        PoiDensity.nativeSet(true, cluster);
      }
    });
  }
}
