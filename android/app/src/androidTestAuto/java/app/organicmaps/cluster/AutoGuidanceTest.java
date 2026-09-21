package app.organicmaps.cluster;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.location.Location;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import app.organicmaps.MwmActivity;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.SplashActivity;
import app.organicmaps.sdk.ChoosePositionMode;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.MapView;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.bookmarks.data.BookmarkInfo;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.bookmarks.data.ParcelablePointD;
import app.organicmaps.sdk.location.ClusterTestLocation;
import app.organicmaps.sdk.location.LocationState;
import app.organicmaps.sdk.routing.RouteMarkType;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.util.ThemeSwitcher;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class AutoGuidanceTest
{
  @Test
  public void mapPointPickerCommitsReplacementAndCancelsWithoutAutoStart() throws Exception
  {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    context.startActivity(new Intent(context, SplashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    MwmActivity activity = awaitMap();
    Thread.sleep(1000);
    Field autoStart = RoutingController.class.getDeclaredField("mStartAfterBuild");
    autoStart.setAccessible(true);
    Router[] previousRouter = new Router[1];
    try
    {
      main(() -> {
        previousRouter[0] = Router.getLastUsed();
        RoutingController controller = RoutingController.get();
        controller.cancel();
        controller.prepare(MapObject.createMapObject(MapObject.API_POINT, "Start", "", 55, 37), null, Router.Ruler);
        controller.waitForPoiPick(RouteMarkType.Finish);
        activity.showPositionChooserForRoutePoint();
        View chooser = activity.findViewById(R.id.position_chooser);
        assertEquals(ChoosePositionMode.Routing, ChoosePositionMode.get());
        assertEquals(View.VISIBLE, chooser.getVisibility());
        chooser.findViewById(R.id.done).performClick();
        assertNotNull(controller.getEndPoint());
        assertFalse(controller.isWaitingPoiPick());
        assertEquals(ChoosePositionMode.None, ChoosePositionMode.get());
      });
      Thread.sleep(300);
      main(() -> {
        RoutingController controller = RoutingController.get();
        controller.waitForPoiReplacement(RouteMarkType.Finish, 0);
        activity.showPositionChooserForRoutePoint();
        try
        {
          autoStart.setBoolean(controller, true);
          activity.findViewById(R.id.position_chooser).findViewById(R.id.done).performClick();
          assertFalse("Manual replacement must cancel a pending auto-start", autoStart.getBoolean(controller));
        }
        catch (IllegalAccessException e)
        {
          throw new AssertionError(e);
        }
        assertFalse(controller.isWaitingPoiPick());
        assertEquals(ChoosePositionMode.None, ChoosePositionMode.get());
        var finish = controller.getEndPoint();
        assertNotNull(finish);
        controller.waitForPoiReplacement(RouteMarkType.Finish, 0);
        activity.showPositionChooserForRoutePoint();
        assertTrue(activity.handleBackPress());
        assertFalse(controller.isWaitingPoiPick());
        assertEquals(ChoosePositionMode.None, ChoosePositionMode.get());
        assertEquals(finish.getLat(), controller.getEndPoint().getLat(), 0);
        assertEquals(finish.getLon(), controller.getEndPoint().getLon(), 0);
      });
    }
    finally
    {
      main(() -> {
        if (ChoosePositionMode.get() == ChoosePositionMode.Routing)
          activity.handleBackPress();
        RoutingController.get().cancel();
        if (previousRouter[0] != null)
          Router.set(previousRouter[0]);
      });
    }
  }

  private static void main(Runnable task)
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
  }

  private static MwmActivity awaitMap() throws InterruptedException
  {
    AtomicReference<MwmActivity> current = new AtomicReference<>();
    long deadline = SystemClock.elapsedRealtime() + 20000;
    do
    {
      main(() -> {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
          if (activity instanceof MwmActivity map)
            current.set(map);
      });
      if (current.get() != null && current.get().findViewById(R.id.map) != null)
        return current.get();
      Thread.sleep(50);
    }
    while (SystemClock.elapsedRealtime() < deadline);
    throw new AssertionError("Map activity did not resume");
  }

  private static VoiceSavedPlaces.Place place(long id, String title) throws Exception
  {
    var constructor = BookmarkInfo.class.getDeclaredConstructor(long.class, long.class, String.class, String.class,
                                                                String.class, int.class, int.class,
                                                                ParcelablePointD.class, double.class, String.class);
    constructor.setAccessible(true);
    double lat = 55.73198, lon = 37.63945;
    double mercatorY = Math.toDegrees(Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2)));
    return new VoiceSavedPlaces.Place(
        constructor.newInstance(0L, id, title, "", "", Color.RED, 0, new ParcelablePointD(lon, mercatorY), 16.0, ""));
  }

  private static Bitmap snapshot(MapView view) throws Exception
  {
    Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
    CountDownLatch copied = new CountDownLatch(1);
    int[] result = {-1};
    main(() -> PixelCopy.request(view, bitmap, code -> {
      result[0] = code;
      copied.countDown();
    }, new Handler(Looper.getMainLooper())));
    assertTrue(copied.await(10, TimeUnit.SECONDS));
    assertEquals(PixelCopy.SUCCESS, result[0]);
    return bitmap;
  }

  @Test
  @SdkSuppress(minSdkVersion = 26)
  public void coordinatesHomeWorkAndBookmarkStartWithVisibleRoute() throws Exception
  {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MwmApplication app = (MwmApplication) context.getApplicationContext();
    context.startActivity(new Intent(context, SplashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    awaitMap();
    Thread.sleep(1000);
    Field placesField = VoiceSavedPlaces.class.getDeclaredField("sPlaces");
    placesField.setAccessible(true);
    Object savedPlaces = placesField.get(null);
    Config.UiTheme[] previousTheme = new Config.UiTheme[1];
    main(() -> previousTheme[0] = Config.UiTheme.getUiThemePreference());
    try
    {
      // Replace only the in-memory provider snapshot; no user bookmarks are created or modified.
      placesField.set(null, List.of(place(1001, "home"), place(1002, "work"), place(1003, "Route test")));
      String[] targets = {"lat_to=55.73198&lon_to=37.63945", "place_to=home", "place_to=work", "place_to=1003"};
      for (int attempt = 0; attempt < targets.length; ++attempt)
      {
        main(() -> {
          RoutingController.get().cancel();
          Config.UiTheme.setUiThemePreference(Config.UiTheme.LIGHT);
          ThemeSwitcher.INSTANCE.synchronizeApplicationTheme();
          if (!app.getLocationHelper().isActive())
            app.getLocationHelper().start();
          if (LocationState.getMode() == LocationState.NOT_FOLLOW_NO_POSITION)
            LocationState.nativeSwitchToNextMode();
          Location location = new Location("test-auto-guidance");
          location.setLatitude(55.70839);
          location.setLongitude(37.62145);
          location.setAccuracy(1);
          location.setSpeed(0);
          location.setBearing(0);
          location.setTime(System.currentTimeMillis());
          location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
          app.getLocationHelper().onLocationChanged(location);
          ClusterTestLocation.setCoreLocation(55.70839, 37.62145, 0, 0);
          assertNotNull(app.getLocationHelper().getMyPosition());
        });
        // The native my-position mark is updated by the renderer, after the Java GPS callback.
        long positionDeadline = SystemClock.elapsedRealtime() + 10000;
        int[] mode = {LocationState.PENDING_POSITION};
        do
        {
          main(() -> mode[0] = LocationState.getMode());
          if (mode[0] != LocationState.PENDING_POSITION && mode[0] != LocationState.NOT_FOLLOW_NO_POSITION)
            break;
          Thread.sleep(50);
        }
        while (SystemClock.elapsedRealtime() < positionDeadline);
        assertNotEquals(LocationState.PENDING_POSITION, mode[0]);
        assertNotEquals(LocationState.NOT_FOLLOW_NO_POSITION, mode[0]);
        context.startActivity(new Intent(Intent.ACTION_VIEW,
                                         Uri.parse("om://build_route_on_map?" + targets[attempt] + "&start_guidance=1"),
                                         context, MwmActivity.class)
                                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        long deadline = SystemClock.elapsedRealtime() + 60000;
        boolean[] navigating = {false};
        do
        {
          main(() -> navigating[0] = RoutingController.get().isNavigating());
          if (navigating[0])
            break;
          Thread.sleep(100);
        }
        while (SystemClock.elapsedRealtime() < deadline);
        assertTrue("No automatic guidance for " + targets[attempt], navigating[0]);
        // Allow the navigation-style recache and native follow transition to finish; a planning
        // frame with a route is not evidence that its line survived automatic guidance.
        Thread.sleep(2500);
        MapView map = awaitMap().findViewById(R.id.map);
        int routePixels = 0;
        deadline = SystemClock.elapsedRealtime() + 10000;
        do
        {
          Thread.sleep(250);
          Bitmap bitmap = snapshot(map);
          routePixels = 0;
          for (int y = 0; y < bitmap.getHeight(); y += 2)
            for (int x = 0; x < bitmap.getWidth(); x += 2)
            {
              int color = bitmap.getPixel(x, y);
              if (Color.green(color) > 170 && Color.red(color) < 190 && Color.green(color) > Color.red(color) * 1.3
                  && Color.green(color) > Color.blue(color) * 1.5)
                ++routePixels;
            }
          try (FileOutputStream out =
                   new FileOutputStream(new File(context.getCacheDir(), "auto-route-" + attempt + ".png")))
          {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
          }
          bitmap.recycle();
          if (routePixels > 40)
            break;
        }
        while (SystemClock.elapsedRealtime() < deadline);
        assertTrue("Navigation has no route line for " + targets[attempt] + ": " + routePixels, routePixels > 40);
      }
    }
    finally
    {
      placesField.set(null, savedPlaces);
      main(() -> {
        RoutingController.get().cancel();
        Config.UiTheme.setUiThemePreference(previousTheme[0]);
        ThemeSwitcher.INSTANCE.synchronizeApplicationTheme();
      });
    }
  }
}
