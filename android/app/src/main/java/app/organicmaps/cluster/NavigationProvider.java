package app.organicmaps.cluster;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.location.Location;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import app.organicmaps.BuildConfig;
import app.organicmaps.MwmApplication;
import app.organicmaps.road.RoadDataManager;
import app.organicmaps.road.RoadEventLabels;
import app.organicmaps.road.RoadEventWarnings;
import app.organicmaps.road.RoadWarningAudio;
import app.organicmaps.routing.SpeedWarningController;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.cluster.ClusterCamera;
import app.organicmaps.sdk.cluster.ClusterFlag;
import app.organicmaps.sdk.cluster.ClusterMap;
import app.organicmaps.sdk.cluster.ClusterScale;
import app.organicmaps.sdk.cluster.ClusterZoom;
import app.organicmaps.sdk.cluster.RoadInfo;
import app.organicmaps.sdk.location.LocationHelper;
import app.organicmaps.sdk.road.RoadEvents;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.widget.roadshield.RoadShieldDrawable;
import app.organicmaps.settings.SpeedWarningSettings;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/** Navigation protocol consumed by instrument-cluster hosts, including RoxPremium. */
public final class NavigationProvider extends ContentProvider
{
  public static final String AUTHORITY = "organicmaps.auto.navi";
  public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

  private static final String[] DATA_PATHS = {"guidance",        "maneuver", "lanes",      "speed_camera",
                                              "direction_signs", "routes",   "road_events"};
  private static volatile NavigationSnapshot sSnapshot = NavigationSnapshot.EMPTY;
  @Nullable
  private static NavigationSnapshot sNotifiedSnapshot;
  private static boolean sInitialized;
  private static MwmApplication sApplication;
  private static RoadEventWarnings sRoadWarnings;
  @Nullable
  private static SpeedWarningController sSpeedWarnings;
  private static long sPublishedAt;
  private static RoadInfo sRoadInfo = RoadInfo.EMPTY;
  private static long sRoadFixNanos;
  private static long sFixNanos;
  private static RoadInfoMonitor sRoadMonitor;
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static Runnable sExpired;
  private static boolean sSpeedNotificationPending;
  private static long sSpeedPublishedAt;

  /** Shares the already matched road with the main map; no provider query or extra map read. */
  public static double getCurrentSpeedLimitMps()
  {
    return sSnapshot.currentSpeedLimitMps(SystemClock.elapsedRealtimeNanos());
  }

  public static void initialize(MwmApplication app)
  {
    if (sInitialized)
      return;
    sInitialized = true;
    sApplication = app;
    RoadWarningAudio audio = RoadDataManager.available() ? new RoadWarningAudio(app) : null;
    if (SpeedWarningSettings.isAvailable())
      sSpeedWarnings = new SpeedWarningController(app, audio);
    VoiceSavedPlaces.initialize(app);
    sRoadMonitor = new RoadInfoMonitor((info, fixNanos) -> {
      sRoadInfo = info;
      sRoadFixNanos = fixNanos;
      publish(app, true);
    });
    sExpired = () -> publish(app, true);
    if (RoadDataManager.available())
    {
      sRoadWarnings = new RoadEventWarnings(app, audio);
      RoadDataManager.get(app).initialize();
    }
    app.getLocationHelper().addListener(location -> onLocation(app, location));
    app.getLocationHelper().addDisplaySpeedListener(() -> {
      if (sSpeedWarnings != null)
        sSpeedWarnings.update();
      if (sSpeedNotificationPending)
        return;
      sSpeedNotificationPending = true;
      MAIN.postDelayed(() -> {
        sSpeedNotificationPending = false;
        sSpeedPublishedAt = SystemClock.elapsedRealtime();
        if (sRoadWarnings != null)
        {
          NavigationSnapshot current = sSnapshot.validAt(SystemClock.elapsedRealtimeNanos());
          sRoadWarnings.update(current.roadInfo, current.camera);
        }
        for (String path : new String[] {"speed", "guidance"})
          app.getContentResolver().notifyChange(Uri.withAppendedPath(CONTENT_URI, path), null);
      }, Math.max(0, 250 - (SystemClock.elapsedRealtime() - sSpeedPublishedAt)));
    });
    RoutingController.get().addNavigationStateListener(active -> {
      if (RoadDataManager.available())
        RoadDataManager.get(app).configure();
      publish(app, true);
    });
    publish(app, true);
  }

  static void onLocation(MwmApplication app, Location location)
  {
    sFixNanos = location.getElapsedRealtimeNanos();
    MAIN.removeCallbacks(sExpired);
    long age = Math.max(0, (SystemClock.elapsedRealtimeNanos() - sFixNanos) / 1_000_000);
    MAIN.postDelayed(sExpired, Math.max(0, RoadInfoMonitor.MAX_FIX_AGE_MS + 1 - age));
    if (RoadDataManager.available())
      RoadDataManager.get(app).onLocation(location);
    if (!RoutingController.get().isNavigating() || (RoadDataManager.available() && RoadDataManager.get(app).enabled()))
      sRoadMonitor.update(location);
    // LocationHelper delivers listeners before forwarding the same fix to native routing.
    MAIN.post(() -> publish(app, false));
  }

  private static void publish(Context context, boolean force)
  {
    boolean navigating = RoutingController.get().isNavigating();
    long now = SystemClock.elapsedRealtime();
    if (!force && now - sPublishedAt < 250)
      return;
    sPublishedAt = now;
    RoutingInfo info = navigating ? Framework.nativeGetRouteFollowingInfo() : null;
    boolean freshRoad =
        SystemClock.elapsedRealtimeNanos() - sRoadFixNanos >= 0
        && SystemClock.elapsedRealtimeNanos() - sRoadFixNanos <= RoadInfoMonitor.MAX_FIX_AGE_MS * 1_000_000L;
    RoadInfo road = freshRoad ? sRoadInfo : RoadInfo.EMPTY;
    boolean external = RoadDataManager.available() && RoadDataManager.get(context).enabled();
    if (navigating && !external)
      road = RoadInfo.EMPTY;
    double[] nativeCamera = navigating ? ClusterMap.nativeGetCameraAhead() : new double[0];
    double[] camera = navigating ? nativeCamera : road.camera;
    if (external && road.camera.length == 5 && (camera.length != 5 || road.camera[0] < camera[0]))
      camera = road.camera;
    sSnapshot = new NavigationSnapshot(info, camera, navigating ? ClusterMap.nativeGetRouteMetrics() : new double[3],
                                       road, navigating ? sFixNanos : sRoadFixNanos);
    // Compare with the last published effective state, not an old snapshot re-evaluated at the new time:
    // expiry must notify consumers once so they clear previously visible instructions.
    NavigationSnapshot current = sSnapshot.validAt(SystemClock.elapsedRealtimeNanos());
    NavigationSnapshot previous = sNotifiedSnapshot;
    sNotifiedSnapshot = current;
    for (String path : DATA_PATHS)
      if (previous == null || !current.hasSameData(path, previous))
        context.getContentResolver().notifyChange(Uri.withAppendedPath(CONTENT_URI, path), null);
    if (sSpeedWarnings != null)
      sSpeedWarnings.update();
    if (sRoadWarnings != null)
      sRoadWarnings.update(current.roadInfo, current.camera);
  }

  public static void invalidateRoadData()
  {
    if (sApplication == null || sRoadMonitor == null)
      return;
    sRoadMonitor.invalidate();
    sRoadInfo = RoadInfo.EMPTY;
    sRoadFixNanos = 0;
    publish(sApplication, true);
  }

  @Override
  public boolean onCreate()
  {
    return true;
  }

  @Nullable
  @Override
  public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                      @Nullable String[] selectionArgs, @Nullable String sortOrder)
  {
    String path = uri.getPath();
    if ("/show_cluster".equals(path) || "/hide_cluster".equals(path))
    {
      Bundle args = new Bundle();
      args.putInt("displayId", Integer.parseInt(uri.getQueryParameter("displayId")));
      String zoom = uri.getQueryParameter("zoom");
      args.putInt("zoom", ClusterZoom.parse(zoom));
      args.putDouble("scale", ClusterScale.parse(uri.getQueryParameter("scale")));
      args.putBoolean("poi", ClusterFlag.parse("poi", uri.getQueryParameter("poi")));
      args.putBoolean("3d", ClusterFlag.parse("3d", uri.getQueryParameter("3d")));
      for (String key : new String[] {"tilt", "anchor", "anchor_x", "anchor_y"})
        args.putString(key, uri.getQueryParameter(key));
      command("/show_cluster".equals(path) ? ClusterDisplayService.SHOW : ClusterDisplayService.HIDE, args);
      return cursor(new String[] {"accepted"}, new Object[] {1});
    }
    NavigationSnapshot snapshot = sSnapshot;
    long fixAge = snapshot.fixAgeMillis(SystemClock.elapsedRealtimeNanos());
    if (fixAge > RoadInfoMonitor.MAX_FIX_AGE_MS)
      snapshot = NavigationSnapshot.EMPTY;
    RoutingInfo info = snapshot.info;
    LocationHelper.DisplaySpeed speed = MwmApplication.from(providerContext()).getLocationHelper().getDisplaySpeed();
    Object speedValue = speed == null ? null : speed.speedMps();
    int speedValid = speed == null ? 0 : 1;
    String speedSource = speed == null ? "none" : speed.source();
    long speedAge = speed == null ? -1 : (SystemClock.elapsedRealtimeNanos() - speed.timestampNanos()) / 1_000_000L;
    MatrixCursor result;
    switch (path == null ? "" : path)
    {
    case "/bookmarks":
    {
      int uid = Binder.getCallingUid();
      if (uid != 0 && uid != 1000 && uid != 2000 && uid != Process.myUid())
        throw new SecurityException("Saved places are shared only with the app and system navigation assistants");
      result = new MatrixCursor(new String[] {"id", "title", "lat", "lon"});
      for (var place : VoiceSavedPlaces.get())
        result.addRow(new Object[] {Long.toString(place.id), place.title, place.latitude, place.longitude});
      break;
    }
    case "/road_events_status":
      long[] state = sInitialized ? RoadEvents.nativeGetState() : new long[3];
      result =
          cursor(new String[] {"enabled", "indexed_events", "revision"}, new Object[] {state[0], state[1], state[2]});
      break;
    case "/road_events":
      result = new MatrixCursor(new String[] {"id", "kind", "distance", "speed", "lat", "lon", "name"});
      if (snapshot.roadInfo.event.length == 5)
      {
        double[] event = snapshot.roadInfo.event;
        result.addRow(new Object[] {snapshot.roadInfo.eventId, (int) event[0], event[1], event[2], event[3], event[4],
                                    RoadEventLabels.name(providerContext(), (int) event[0])});
      }
      break;
    case "/api_version": result = cursor(new String[] {"version"}, new Object[] {1}); break;
    case "/speed":
      result = cursor(new String[] {"speed", "speed_valid", "speed_source", "speed_unit", "speed_age_ms"},
                      new Object[] {speedValue, speedValid, speedSource, "m/s", speedAge});
      break;
    case "/guidance":
      result = cursor(new String[] {"state",
                                    "speed_limit",
                                    "distance_left",
                                    "distance_total",
                                    "distance_unit",
                                    "display_distance_left",
                                    "display_distance_unit",
                                    "time_left",
                                    "display_time_left",
                                    "current_road",
                                    "overview",
                                    "current_city",
                                    "current_region",
                                    "speed_limit_valid",
                                    "fix_age_ms",
                                    "road_matched",
                                    "speed",
                                    "speed_valid",
                                    "speed_source",
                                    "speed_unit",
                                    "speed_age_ms"},
                      info == null
                          ? new Object[] {"none",      snapshot.roadInfo.speedLimitMps,
                                          0,           0,
                                          "m",         "",
                                          "m",         0,
                                          "",          snapshot.roadInfo.road,
                                          "none",      "",
                                          "",          snapshot.roadInfo.speedLimitMps > 0 ? 1 : 0,
                                          fixAge,      snapshot.roadInfo.matched ? 1 : 0,
                                          speedValue,  speedValid,
                                          speedSource, "m/s",
                                          speedAge}
                          : new Object[] {"active",
                                          snapshot.currentSpeedLimitMps(SystemClock.elapsedRealtimeNanos()),
                                          (int) Math.round(snapshot.metrics[0]),
                                          (int) Math.round(snapshot.metrics[1]),
                                          "m",
                                          info.distToTarget.mDistanceStr,
                                          NavigationSnapshot.unit(info.distToTarget),
                                          info.totalTimeInSeconds,
                                          app.organicmaps.util.Utils
                                              .formatRoutingTime(providerContext(), info.totalTimeInSeconds,
                                                                 app.organicmaps.R.dimen.text_size_routing_number)
                                              .toString(),
                                          info.currentStreet,
                                          "none",
                                          "",
                                          "",
                                          snapshot.currentSpeedLimitMps(SystemClock.elapsedRealtimeNanos()) > 0 ? 1 : 0,
                                          fixAge,
                                          1,
                                          speedValue,
                                          speedValid,
                                          speedSource,
                                          "m/s",
                                          speedAge});
      break;
    case "/maneuver":
      result = new MatrixCursor(new String[] {"action", "distance", "distance_unit", "display_distance",
                                              "display_distance_unit", "next_road_name", "exit_number"});
      if (info != null)
        result.addRow(new Object[] {NavigationSnapshot.action(info.carDirection), (int) Math.round(snapshot.metrics[2]),
                                    "m", info.distToTurn.mDistanceStr, NavigationSnapshot.unit(info.distToTurn),
                                    info.nextStreet, info.exitNum});
      break;
    case "/lanes":
      result = new MatrixCursor(new String[] {"directions", "highlighted_direction", "kind", "distance",
                                              "distance_unit", "display_distance", "display_distance_unit"});
      if (info != null && info.lanes != null)
        for (var lane : info.lanes)
          result.addRow(new Object[] {
              Arrays.stream(lane.mLaneWays).map(NavigationSnapshot::lane).collect(Collectors.joining(",")),
              NavigationSnapshot.lane(lane.mActiveLaneWay), "unknown_kind", (int) Math.round(snapshot.metrics[2]), "m",
              info.distToTurn.mDistanceStr, NavigationSnapshot.unit(info.distToTurn)});
      break;
    case "/speed_camera":
      result = new MatrixCursor(
          new String[] {"distance", "speed_limit", "distance_unit", "camera_id", "tolerance_exceeded"});
      result.addRow(snapshot.cameraRow());
      break;
    case "/direction_signs":
      result =
          new MatrixCursor(new String[] {"action", "display_distance", "kind", "distance", "icon", "text", "text_color",
                                         "background_color", "distance_unit", "display_distance_unit"});
      if (info != null && info.nextStreetRoadShields != null && info.nextStreetRoadShields.hasTargetRoadShields())
        for (var shield : info.nextStreetRoadShields.targetRoadShields)
          result.addRow(new Object[] {
              NavigationSnapshot.action(info.carDirection), info.distToTurn.mDistanceStr, "road",
              (int) Math.round(snapshot.metrics[2]), "", shield.text, RoadShieldDrawable.getTextColor(shield.type),
              RoadShieldDrawable.getBackgroundColor(shield.type), "m", NavigationSnapshot.unit(info.distToTurn)});
      break;
    case "/routes":
      result = new MatrixCursor(new String[] {"index", "distance", "unit", "travel_time", "is_selected"});
      if (info != null)
        result.addRow(new Object[] {0, (int) Math.round(snapshot.metrics[1]), "m", info.totalTimeInSeconds, 1});
      break;
    default: throw new IllegalArgumentException("Unknown navigation URI: " + uri);
    }
    result.setNotificationUri(providerContext().getContentResolver(), uri);
    return project(result, projection);
  }

  private static MatrixCursor cursor(String[] columns, Object[] row)
  {
    MatrixCursor result = new MatrixCursor(columns);
    result.addRow(row);
    return result;
  }

  private Cursor project(MatrixCursor source, @Nullable String[] projection)
  {
    if (projection == null)
      return source;
    MatrixCursor result = new MatrixCursor(projection);
    while (source.moveToNext())
    {
      Object[] row = new Object[projection.length];
      for (int i = 0; i < projection.length; ++i)
      {
        int column = source.getColumnIndexOrThrow(projection[i]);
        row[i] = switch (source.getType(column))
        {
          case Cursor.FIELD_TYPE_INTEGER -> source.getLong(column);
          case Cursor.FIELD_TYPE_FLOAT -> source.getDouble(column);
          case Cursor.FIELD_TYPE_NULL -> null;
          default -> source.getString(column);
        };
      }
      result.addRow(row);
    }
    result.setNotificationUri(providerContext().getContentResolver(), source.getNotificationUri());
    source.close();
    return result;
  }

  @Nullable
  @Override
  public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras)
  {
    Bundle args = extras == null ? new Bundle() : extras;
    if ("set_mock_location".equals(method) || "clear_mock_location".equals(method))
    {
      if (!BuildConfig.DEBUG)
        throw new UnsupportedOperationException("Mock location commands are only available in debug builds");
      int uid = Binder.getCallingUid();
      if (uid != 0 && uid != 2000 && uid != Process.myUid())
        throw new SecurityException("Mock location commands require adb shell or the app UID");
      long token = Binder.clearCallingIdentity();
      try
      {
        return "set_mock_location".equals(method) ? DebugMockLocation.set(providerContext(), args)
                                                    : DebugMockLocation.clear(providerContext());
      }
      finally
      {
        Binder.restoreCallingIdentity(token);
      }
    }
    String action = switch (method)
    {
      case "show_cluster" -> ClusterDisplayService.SHOW;
      case "hide_cluster" -> ClusterDisplayService.HIDE;
      default -> throw new IllegalArgumentException("Unknown navigation command: " + method);
    };
    command(action, args);
    Bundle result = new Bundle();
    result.putBoolean("accepted", true);
    return result;
  }

  private void command(String action, Bundle args)
  {
    Context context = providerContext();
    context.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION,
                                           "Navigation access requires location permission");
    if (!ClusterDisplayService.HIDE.equals(action)
        && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
               != android.content.pm.PackageManager.PERMISSION_GRANTED)
      throw new SecurityException("Grant location permission in Organic Maps before connecting a display");
    int uid = Binder.getCallingUid();
    int displayId = args.getInt("displayId", -1);
    int zoom = ClusterZoom.parse(args.get("zoom"));
    double scale = ClusterScale.parse(args.get("scale"));
    boolean poiVisible = ClusterFlag.parse("poi", args.get("poi"));
    boolean buildings3d = ClusterFlag.parse("3d", args.get("3d"));
    ClusterCamera camera =
        ClusterCamera.parse(args.get("tilt"), args.get("anchor"), args.get("anchor_x"), args.get("anchor_y"));
    if (displayId <= 0)
      throw new IllegalArgumentException("Invalid cluster display or zoom");
    Intent intent = new Intent(context, ClusterDisplayService.class)
                        .setAction(action)
                        .putExtra("displayId", displayId)
                        .putExtra("uid", uid)
                        .putExtra("zoom", zoom)
                        .putExtra("scale", scale)
                        .putExtra("poi", poiVisible)
                        .putExtra("3d", buildings3d)
                        .putExtra("tilt", camera.tilt)
                        .putExtra("anchor_x", camera.anchorX)
                        .putExtra("anchor_y", camera.anchorY);
    Bundle lease = new Bundle();
    lease.putBinder("token", args.getBinder("token"));
    intent.putExtras(lease);
    long token = Binder.clearCallingIdentity();
    try
    {
      ContextCompat.startForegroundService(context, intent);
    }
    finally
    {
      Binder.restoreCallingIdentity(token);
    }
  }

  private Context providerContext()
  {
    return Objects.requireNonNull(getContext());
  }
  @Nullable
  @Override
  public String getType(@NonNull Uri uri)
  {
    return "vnd.android.cursor.dir/vnd.organicmaps.navigation";
  }
  @Nullable
  @Override
  public Uri insert(@NonNull Uri uri, @Nullable ContentValues values)
  {
    throw new UnsupportedOperationException();
  }
  @Override
  public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] args)
  {
    throw new UnsupportedOperationException();
  }
  @Override
  public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                    @Nullable String[] args)
  {
    throw new UnsupportedOperationException();
  }
}
