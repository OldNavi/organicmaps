package app.organicmaps.cluster;

import static org.junit.Assert.*;

import android.location.Location;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.cluster.RoadInfo;
import app.organicmaps.sdk.routing.RoutingController;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class RoadInfoIntegrationTest
{
  @Test
  public void boundRoadDataSessionSurvivesOneClientDisconnect() throws Exception
  {
    MwmApplication application =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    CountDownLatch ready = new CountDownLatch(1);
    main(() -> {
      try
      {
        if (!application.initOrganicMaps(ready::countDown))
          ready.countDown();
      }
      catch (java.io.IOException e)
      {
        throw new AssertionError(e);
      }
    });
    assertTrue(ready.await(60, TimeUnit.SECONDS));
    CountDownLatch connected = new CountDownLatch(2);
    android.content.ServiceConnection[] clients = new android.content.ServiceConnection[2];
    try
    {
      for (int i = 0; i < clients.length; ++i)
      {
        clients[i] = new android.content.ServiceConnection() {
          @Override
          public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder)
          {
            connected.countDown();
          }
          @Override
          public void onServiceDisconnected(android.content.ComponentName name)
          {}
        };
        var client = clients[i];
        main(()
                 -> assertTrue(
                     application.bindService(new android.content.Intent(application, NavigationReadyService.class),
                                             client, android.content.Context.BIND_AUTO_CREATE)));
      }
      assertTrue("Road-data clients did not bind", connected.await(30, TimeUnit.SECONDS));
      main(() -> {
        assertTrue(application.getLocationHelper().hasExternalNavigation());
        assertTrue(application.getLocationHelper().isActive());
        application.unbindService(clients[0]);
        clients[0] = null;
      });
      Thread.sleep(300);
      main(() -> assertTrue(application.getLocationHelper().hasExternalNavigation()));
      main(() -> {
        application.unbindService(clients[1]);
        clients[1] = null;
      });
      Thread.sleep(300);
      main(() -> assertFalse(application.getLocationHelper().hasExternalNavigation()));
    }
    finally
    {
      main(() -> {
        for (var client : clients)
          if (client != null)
            application.unbindService(client);
      });
    }
  }

  private static void main(Runnable action)
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
  }

  @Test
  public void roadLimitIsPublishedWithoutRouteAndExpiresWithoutGps() throws Exception
  {
    MwmApplication application =
        (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    CountDownLatch ready = new CountDownLatch(1);
    main(() -> {
      try
      {
        if (!application.initOrganicMaps(ready::countDown))
          ready.countDown();
      }
      catch (java.io.IOException e)
      {
        throw new AssertionError(e);
      }
    });
    assertTrue(ready.await(60, TimeUnit.SECONDS));
    main(() -> application.getLocationHelper().stop());
    assertFalse(RoutingController.get().isNavigating());

    // Fixed samples on Moscow arterial roads. Requires the already downloaded Moscow map.
    double[][] centers = {{55.76228, 37.60578}, {55.6503, 37.6339}, {55.652, 37.628},
                          {55.622, 37.617},     {55.7552, 37.6196}, {55.8101, 37.5067},
                          {55.775, 37.584},     {55.705, 37.588},   {55.717, 37.624}};
    var samples = new java.util.ArrayList<double[]>();
    for (double[] center : centers)
      for (int y = -2; y <= 2; ++y)
        for (int x = -2; x <= 2; ++x)
          samples.add(new double[] {center[0] + y * 0.0002, center[1] + x * 0.0002});
    double[] selected = null;
    RoadInfo info = RoadInfo.EMPTY;
    double timestamp = System.currentTimeMillis() / 1000.0;
    for (double[] point : samples)
    {
      for (int bearing = 0; bearing < 360; bearing += 30)
      {
        RoadInfo.read(point[0], point[1], 4, 15, bearing, timestamp++);
        info = RoadInfo.read(point[0], point[1], 4, 15, bearing, timestamp++);
        if (info.matched && info.speedLimitMps > 0)
        {
          selected = new double[] {point[0], point[1], bearing};
          break;
        }
      }
      if (selected != null)
        break;
    }
    assertNotNull("No road limit at Moscow samples; check downloaded map", selected);
    double constLimit = info.speedLimitMps;
    for (int second = 0; second < 12; ++second)
    {
      info = RoadInfo.read(selected[0], selected[1], 4, 0, -1, timestamp++);
      assertTrue("Fresh stationary fixes lost the matched road", info.matched);
      assertEquals(constLimit, info.speedLimitMps, 0.01);
    }
    android.util.Log.i("RoadInfoTest", "road=" + info.road + " limit_kmh=" + info.speedLimitMps * 3.6 + " lat="
                                           + selected[0] + " lon=" + selected[1] + " bearing=" + selected[2]);
    for (int i = 0; i < 3; ++i)
    {
      Location location = new Location("test-core-only");
      location.setLatitude(selected[0]);
      location.setLongitude(selected[1]);
      location.setBearing((float) selected[2]);
      location.setSpeed(15);
      location.setAccuracy(4);
      location.setTime(System.currentTimeMillis());
      location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
      main(() -> NavigationProvider.onLocation(application, location));
      Thread.sleep(600);
    }
    Uri guidance = NavigationProvider.CONTENT_URI.buildUpon().appendPath("guidance").build();
    long deadline = SystemClock.elapsedRealtime() + 2500;
    double published = 0;
    do
    {
      try (var cursor = application.getContentResolver().query(guidance, null, null, null, null))
      {
        assertNotNull(cursor);
        assertTrue(cursor.moveToFirst());
        assertEquals("none", cursor.getString(cursor.getColumnIndexOrThrow("state")));
        published = cursor.getDouble(cursor.getColumnIndexOrThrow("speed_limit"));
      }
      if (published > 0)
        break;
      Thread.sleep(100);
    }
    while (SystemClock.elapsedRealtime() < deadline);
    assertEquals(info.speedLimitMps, published, 0.01);
    Thread.sleep(RoadInfoMonitor.MAX_FIX_AGE_MS + 200);
    try (var cursor = application.getContentResolver().query(guidance, null, null, null, null))
    {
      assertNotNull(cursor);
      assertTrue(cursor.moveToFirst());
      assertEquals(0, cursor.getDouble(cursor.getColumnIndexOrThrow("speed_limit")), 0);
      assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("road_matched")));
    }
  }
}
