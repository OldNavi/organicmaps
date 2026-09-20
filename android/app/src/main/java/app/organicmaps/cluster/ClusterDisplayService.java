package app.organicmaps.cluster;

import android.Manifest;
import android.app.PendingIntent;
import android.app.Presentation;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import app.organicmaps.MwmActivity;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.cluster.ClusterCamera;
import app.organicmaps.sdk.cluster.ClusterMap;
import app.organicmaps.sdk.cluster.ClusterScale;
import app.organicmaps.sdk.cluster.ClusterZoom;
import app.organicmaps.sdk.location.TrackRecorder;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.util.log.Logger;
import app.organicmaps.util.ThemeSwitcher;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class ClusterDisplayService extends Service implements DisplayManager.DisplayListener
{
  static final String SHOW = "show";
  static final String HIDE = "hide";
  private static final String CHANNEL = "cluster_navigation";
  private static final int NOTIFICATION_ID = 104;
  private static volatile boolean sConnected;
  private int mLastStartId;
  private final Map<Integer, Connection> mConnections = new HashMap<>();
  private final ClusterSessions mSessions = new ClusterSessions();
  private final Map<Integer, Lease> mLeases = new HashMap<>();
  private final Handler mHandler = new Handler(Looper.getMainLooper());

  private static final class Lease
  {
    final IBinder token;
    final IBinder.DeathRecipient recipient;
    Lease(IBinder binder, IBinder.DeathRecipient deathRecipient)
    {
      token = binder;
      recipient = deathRecipient;
    }
    void close()
    {
      token.unlinkToDeath(recipient, 0);
    }
  }
  private DisplayManager mDisplays;
  private boolean mDestroyed;

  private static final class Connection
  {
    final int uid;
    final Presentation presentation;
    final ClusterMap map;
    Connection(int owner, Presentation window, ClusterMap view)
    {
      uid = owner;
      presentation = window;
      map = view;
    }
    void close()
    {
      presentation.dismiss();
      map.release();
    }
  }

  public static boolean isConnected()
  {
    return sConnected;
  }

  @Override
  public void onCreate()
  {
    super.onCreate();
    mDisplays = (DisplayManager) getSystemService(DISPLAY_SERVICE);
    mDisplays.registerDisplayListener(this, null);
    NotificationManagerCompat.from(this).createNotificationChannel(
        new NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(app.organicmaps.routing.R.string.navigation_channel_name))
            .build());
    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    var close =
        PendingIntent.getService(this, 0, new Intent(this, ClusterDisplayService.class).setAction("stop"), flags);
    var open = PendingIntent.getActivity(this, 0, new Intent(this, MwmActivity.class), flags);
    var notification = new NotificationCompat.Builder(this, CHANNEL)
                           .setSmallIcon(app.organicmaps.branding.R.drawable.ic_splash)
                           .setContentTitle(getString(app.organicmaps.branding.R.string.app_name))
                           .setContentIntent(open)
                           .addAction(0, getString(app.organicmaps.R.string.close), close)
                           .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                           .setOngoing(true)
                           .setOnlyAlertOnce(true)
                           .build();
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                                  Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0);
  }

  @Override
  public int onStartCommand(@Nullable Intent intent, int flags, int startId)
  {
    mLastStartId = startId;
    if (intent == null || "stop".equals(intent.getAction()))
    {
      mSessions.clear();
      stopSelf();
      return START_NOT_STICKY;
    }
    int displayId = intent.getIntExtra("displayId", -1);
    int uid = intent.getIntExtra("uid", -1);
    if (HIDE.equals(intent.getAction()))
    {
      if (mSessions.disconnect(uid, displayId))
        remove(displayId);
      stopIfUnused();
      return START_NOT_STICKY;
    }
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED)
    {
      stopIfUnused();
      return START_NOT_STICKY;
    }
    int zoom = intent.getIntExtra("zoom", ClusterZoom.DEFAULT);
    double scale = ClusterScale.parse(intent.getDoubleExtra("scale", ClusterScale.DEFAULT));
    boolean poiVisible = intent.getBooleanExtra("poi", false);
    boolean buildings3d = intent.getBooleanExtra("3d", false);
    var camera = new ClusterCamera(intent.getDoubleExtra("tilt", ClusterCamera.AUTO_TILT),
                                   intent.getDoubleExtra("anchor_x", 0.5), intent.getDoubleExtra("anchor_y", 0.75));
    try
    {
      var request = mSessions.connect(uid, displayId, zoom);
      IBinder token = intent.getExtras().getBinder("token");
      Lease previousLease = mLeases.get(displayId);
      if (token == null && previousLease != null)
        token = previousLease.token;
      releaseLease(displayId);
      if (token != null)
      {
        IBinder.DeathRecipient death = () -> mHandler.post(() -> {
          if (mSessions.isCurrent(request))
          {
            mSessions.disconnect(uid, displayId);
            remove(displayId);
            stopIfUnused();
          }
        });
        try
        {
          token.linkToDeath(death, 0);
          mLeases.put(displayId, new Lease(token, death));
        }
        catch (RemoteException e)
        {
          mSessions.disconnect(uid, displayId);
          remove(displayId);
          stopIfUnused();
          return START_NOT_STICKY;
        }
      }
      var app = MwmApplication.from(this);
      Runnable show = () ->
      {
        if (!mDestroyed && mSessions.isCurrent(request))
          show(displayId, uid, zoom, poiVisible, buildings3d, camera, scale);
      };
      if (!app.getOrganicMaps().arePlatformAndCoreInitialized())
      {
        if (!app.initOrganicMaps(show))
          show.run();
      }
      else
        show.run();
    }
    catch (IOException | RuntimeException e)
    {
      if (mSessions.disconnect(uid, displayId))
        remove(displayId);
      Logger.e("ClusterDisplayService", "Cannot show cluster map", e);
      stopIfUnused();
    }
    return START_NOT_STICKY;
  }

  private void show(int displayId, int uid, int zoom, boolean poiVisible, boolean buildings3d, ClusterCamera camera,
                    double scale)
  {
    Display display = mDisplays.getDisplay(displayId);
    if (display == null || displayId == Display.DEFAULT_DISPLAY)
    {
      mSessions.disconnect(uid, displayId);
      releaseLease(displayId);
      stopIfUnused();
      return;
    }
    Connection previous = mConnections.get(displayId);
    if (previous != null)
    {
      if (previous.uid == uid)
      {
        previous.map.set3dBuildings(buildings3d);
        previous.map.setPoiVisible(poiVisible);
        previous.map.setCamera(zoom, camera);
        previous.map.setScale(scale);
      }
      return;
    }
    Presentation presentation = new Presentation(this, display);
    ClusterMap map = new ClusterMap(presentation.getContext());
    map.set3dBuildings(buildings3d);
    map.setPoiVisible(poiVisible);
    map.setCamera(zoom, camera);
    map.setScale(scale);
    map.setOnUnsupported(() -> {
      Connection connection = mConnections.get(displayId);
      if (connection != null && connection.map == map)
      {
        mSessions.disconnect(uid, displayId);
        remove(displayId);
        Logger.w("ClusterDisplayService", "Cannot create renderer for display " + displayId);
        stopIfUnused();
      }
    });
    presentation.requestWindowFeature(Window.FEATURE_NO_TITLE);
    presentation.setContentView(map);
    presentation.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    presentation.getWindow().getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    presentation.setCancelable(false);
    presentation.getWindow().addFlags(
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
    try
    {
      presentation.show();
      presentation.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                                         WindowManager.LayoutParams.MATCH_PARENT);
      mConnections.put(displayId, new Connection(uid, presentation, map));
      sConnected = true;
      MwmApplication.from(this).getLocationHelper().setExternalNavigationActive(true);
      MwmApplication.from(this).getLocationHelper().start();
    }
    catch (WindowManager.InvalidDisplayException | SecurityException e)
    {
      mSessions.disconnect(uid, displayId);
      releaseLease(displayId);
      map.release();
      presentation.dismiss();
      Logger.e("ClusterDisplayService", "Display is unavailable", e);
      stopIfUnused();
    }
  }

  private void releaseLease(int displayId)
  {
    Lease lease = mLeases.remove(displayId);
    if (lease != null)
      lease.close();
  }

  private void remove(int displayId)
  {
    releaseLease(displayId);
    Connection connection = mConnections.remove(displayId);
    if (connection != null)
      connection.close();
    sConnected = !mConnections.isEmpty();
  }

  private void stopIfUnused()
  {
    // A newer show command may already be queued by ActivityManager.
    if (mSessions.isEmpty() && mLastStartId != 0)
      stopSelfResult(mLastStartId);
  }

  @Override
  public void onConfigurationChanged(Configuration config)
  {
    super.onConfigurationChanged(config);
    if (!mConnections.isEmpty())
      ThemeSwitcher.INSTANCE.synchronizeMapStyle(mConnections.values().iterator().next().presentation.getContext(),
                                                 true);
  }

  @Override
  public void onDisplayAdded(int displayId)
  {}
  @Override
  public void onDisplayChanged(int displayId)
  {}
  @Override
  public void onDisplayRemoved(int displayId)
  {
    if (mSessions.removeDisplay(displayId))
    {
      remove(displayId);
      stopIfUnused();
    }
  }

  @Override
  public void onDestroy()
  {
    mDestroyed = true;
    mDisplays.unregisterDisplayListener(this);
    for (Connection connection : mConnections.values())
      connection.close();
    mConnections.clear();
    mSessions.clear();
    for (Lease lease : mLeases.values())
      lease.close();
    mLeases.clear();
    sConnected = false;
    var app = MwmApplication.from(this);
    app.getLocationHelper().setExternalNavigationActive(false);
    if (app.getOrganicMaps().arePlatformAndCoreInitialized() && app.getTopActivity() == null
        && !app.getLocationHelper().hasExternalNavigation() && !app.getDisplayManager().isCarDisplayUsed()
        && !RoutingController.get().isNavigating() && !TrackRecorder.nativeIsTrackRecordingEnabled())
      app.getLocationHelper().stop();
    super.onDestroy();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent)
  {
    return null;
  }
}
