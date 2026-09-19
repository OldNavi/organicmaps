package app.organicmaps.cluster;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import app.organicmaps.BuildConfig;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.cluster.ClusterCamera;
import app.organicmaps.sdk.cluster.ClusterFlag;
import app.organicmaps.sdk.cluster.ClusterMap;
import app.organicmaps.sdk.cluster.ClusterZoom;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.RoutingInfo;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Navigation protocol consumed by instrument-cluster hosts, including RoxPremium. */
public final class NavigationProvider extends ContentProvider
{
  public static final String AUTHORITY = "organicmaps.auto.navi";
  public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

  private static final String[] DATA_PATHS = {"guidance",     "maneuver",        "lanes",
                                              "speed_camera", "direction_signs", "routes"};
  private static volatile NavigationSnapshot sSnapshot = NavigationSnapshot.EMPTY;
  private static boolean sInitialized;
  private static long sPublishedAt;

  public static void initialize(MwmApplication app)
  {
    if (sInitialized)
      return;
    sInitialized = true;
    VoiceSavedPlaces.initialize(app);
    app.getLocationHelper().addListener(location -> publish(app, false));
    RoutingController.get().addNavigationStateListener(active -> publish(app, true));
    publish(app, true);
  }

  private static void publish(Context context, boolean force)
  {
    boolean navigating = RoutingController.get().isNavigating();
    long now = android.os.SystemClock.elapsedRealtime();
    if (!force && ((!navigating && sSnapshot.info == null) || now - sPublishedAt < 250))
      return;
    sPublishedAt = now;
    RoutingInfo info = navigating ? Framework.nativeGetRouteFollowingInfo() : null;
    sSnapshot = new NavigationSnapshot(info, navigating ? ClusterMap.nativeGetCameraAhead() : new double[0],
                                       navigating ? ClusterMap.nativeGetRouteMetrics() : new double[3]);
    for (String path : DATA_PATHS)
      context.getContentResolver().notifyChange(Uri.withAppendedPath(CONTENT_URI, path), null);
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
      args.putBoolean("poi", ClusterFlag.parse("poi", uri.getQueryParameter("poi")));
      args.putBoolean("3d", ClusterFlag.parse("3d", uri.getQueryParameter("3d")));
      for (String key : new String[] {"tilt", "anchor", "anchor_x", "anchor_y"})
        args.putString(key, uri.getQueryParameter(key));
      command("/show_cluster".equals(path) ? ClusterDisplayService.SHOW : ClusterDisplayService.HIDE, args);
      return cursor(new String[] {"accepted"}, new Object[] {1});
    }
    NavigationSnapshot snapshot = sSnapshot;
    RoutingInfo info = snapshot.info;
    MatrixCursor result;
    switch (path == null ? "" : path)
    {
    case "/bookmarks":
    {
      int uid = Binder.getCallingUid();
      if (uid != 0 && uid != 1000 && uid != 2000 && uid != android.os.Process.myUid())
        throw new SecurityException("Saved places are shared only with the app and system navigation assistants");
      result = new MatrixCursor(new String[] {"id", "title", "lat", "lon"});
      for (var place : VoiceSavedPlaces.get())
        result.addRow(new Object[] {Long.toString(place.id), place.title, place.latitude, place.longitude});
      break;
    }
    case "/api_version": result = cursor(new String[] {"version"}, new Object[] {1}); break;
    case "/guidance":
      result = cursor(new String[] {"state", "speed_limit", "distance_left", "distance_total", "distance_unit",
                                    "display_distance_left", "display_distance_unit", "time_left", "display_time_left",
                                    "current_road", "overview", "current_city", "current_region"},
                      info == null
                          ? new Object[] {"none", 0.0, 0, 0, "m", "", "m", 0, "", "", "none", "", ""}
                          : new Object[] {"active", NavigationSnapshot.speedLimitMps(info.speedLimitMps),
                                          (int) Math.round(snapshot.metrics[0]), (int) Math.round(snapshot.metrics[1]),
                                          "m", info.distToTarget.mDistanceStr,
                                          NavigationSnapshot.unit(info.distToTarget), info.totalTimeInSeconds,
                                          app.organicmaps.util.Utils
                                              .formatRoutingTime(providerContext(), info.totalTimeInSeconds,
                                                                 app.organicmaps.R.dimen.text_size_routing_number)
                                              .toString(),
                                          info.currentStreet, "none", "", ""});
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
          result.addRow(new Object[] {NavigationSnapshot.action(info.carDirection), info.distToTurn.mDistanceStr,
                                      "road", (int) Math.round(snapshot.metrics[2]), "", shield.text, 0xff000000,
                                      0xffffffff, "m", NavigationSnapshot.unit(info.distToTurn)});
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
      if (uid != 0 && uid != 2000 && uid != android.os.Process.myUid())
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
    return java.util.Objects.requireNonNull(getContext());
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
