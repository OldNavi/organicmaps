package app.organicmaps.sdk.location;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.annotation.UiThread;
import androidx.core.content.ContextCompat;
import androidx.core.location.GnssStatusCompat;
import androidx.core.location.LocationCompat;
import androidx.core.location.LocationManagerCompat;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Map;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.JunctionInfo;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.sdk.util.NetworkPolicy;
import app.organicmaps.sdk.util.log.Logger;
import org.chromium.base.ObserverList;

public class LocationHelper implements BaseLocationProvider.Listener
{
  // Replaced 0 with 100ms. Seems like it is not working on Lineage, EOS, MicroG, ...
  // https://github.com/organicmaps/organicmaps/issues/11076
  // Probably https://github.com/organicmaps/organicmaps/issues/10133
  // Probably https://github.com/organicmaps/organicmaps/issues/9018
  private static final long INTERVAL_FOLLOW_MS = 100;
  private static final long INTERVAL_NOT_FOLLOW_MS = 3000;
  private static final long INTERVAL_NAVIGATION_MS = 100;
  private static final long INTERVAL_TRACK_RECORDING = 100;

  private static final long AGPS_EXPIRATION_TIME_MS = 16 * 60 * 60 * 1000; // 16 hours
  private static final long LOCATION_UPDATE_TIMEOUT_MS = 30 * 1000; // 30 seconds

  @NonNull
  private final Context mContext;
  @NonNull
  private final SensorHelper mSensorHelper;
  private final VehicleSpeedSource mVehicleSpeedSource;
  private final VehicleSpeedSource mMcuSpeedSource;
  private final ObserverList<Runnable> mDisplaySpeedListeners = new ObserverList<>();
  @Nullable
  private volatile DisplaySpeed mDisplaySpeed;
  private final Runnable mDisplaySpeedExpiry = this::updateDisplaySpeed;

  public record DisplaySpeed(double speedMps, String source, long timestampNanos, long maxAgeNanos)
  {
    public boolean isFresh(long now)
    {
      return timestampNanos > 0 && timestampNanos <= now && now - timestampNanos <= maxAgeNanos;
    }
  }

  private static final String TAG = LocationState.LOCATION_TAG;

  private final ObserverList<LocationListener> mListeners = new ObserverList<>();
  private final ObserverList.RewindableIterator<LocationListener> mListenersIterator = mListeners.rewindableIterator();

  @Nullable
  private Location mSavedLocation;
  @Nullable
  private Location mRawLocation;
  private MapObject mMyPosition;
  @NonNull
  private final LocationProviderFactory mLocationProviderFactory = new LocationProviderFactory();
  @NonNull
  private BaseLocationProvider mLocationProvider;
  @Nullable
  private BaseLocationProvider mOldLocationProvider;
  private long mInterval;
  private boolean mInFirstRun;
  private boolean mActive;
  private boolean mExternalNavigationActive;
  private boolean mExternalRoadInfoActive;

  public void setExternalRoadInfoActive(boolean active)
  {
    mExternalRoadInfoActive = active;
    if (isActive() && LocationUtils.checkLocationPermission(mContext))
      restartWithNewMode();
  }

  public boolean hasExternalNavigation()
  {
    return mExternalNavigationActive || mExternalRoadInfoActive;
  }

  public void setExternalNavigationActive(boolean active)
  {
    mExternalNavigationActive = active;
    if (isActive() && LocationUtils.checkLocationPermission(mContext))
      restartWithNewMode();
  }
  private final Handler mHandler;
  private final Runnable mLocationTimeoutRunnable = this::notifyLocationUpdateTimeout;

  @NonNull
  private final GnssStatusCompat.Callback mGnssStatusCallback = new GnssStatusCompat.Callback() {
    @Override
    public void onStarted()
    {
      Logger.d(TAG);
    }

    @Override
    public void onStopped()
    {
      Logger.d(TAG);
    }

    @Override
    public void onFirstFix(int ttffMillis)
    {
      Logger.d(TAG, "ttffMillis = " + ttffMillis);
    }

    @Override
    public void onSatelliteStatusChanged(@NonNull GnssStatusCompat status)
    {
      int used = 0;
      boolean fixed = false;
      for (int i = 0; i < status.getSatelliteCount(); i++)
      {
        if (status.usedInFix(i))
        {
          used++;
          fixed = true;
        }
      }
      Logger.d(TAG, "total = " + status.getSatelliteCount() + " used = " + used + " fixed = " + fixed);
    }
  };

  public LocationHelper(@NonNull Context context, @NonNull SensorHelper sensorHelper)
  {
    mContext = context;
    mSensorHelper = sensorHelper;
    mVehicleSpeedSource = new VehicleSpeedSource(mContext, this::onVehicleSpeedChanged);
    mMcuSpeedSource = new VehicleSpeedSource(mContext, "speedMCU", (speed, timestamp, valid) -> updateDisplaySpeed());
    mLocationProvider = mLocationProviderFactory.getProvider(mContext, this);
    mHandler = new Handler(Looper.getMainLooper());
  }

  /**
   * @return MapObject.MY_POSITION, null if location is not yet determined or "My position" button is switched off.
   */
  @Nullable
  public MapObject getMyPosition()
  {
    if (!isActive())
    {
      mMyPosition = null;
      return null;
    }

    if (mSavedLocation == null)
      return null;

    if (mMyPosition == null)
      mMyPosition = MapObject.createMapObject(MapObject.MY_POSITION, "", "", mSavedLocation.getLatitude(),
                                              mSavedLocation.getLongitude());

    return mMyPosition;
  }

  /**
   * Obtains last known location.
   * @return {@code null} if no location is saved.
   */
  @Nullable
  public Location getSavedLocation()
  {
    return mSavedLocation;
  }

  /** Immutable display-only snapshot, also safe to read from ContentProvider binder threads. */
  @Nullable
  public DisplaySpeed getDisplaySpeed()
  {
    DisplaySpeed speed = mDisplaySpeed;
    return speed != null && speed.isFresh(SystemClock.elapsedRealtimeNanos()) ? speed : null;
  }

  @UiThread
  public void addDisplaySpeedListener(Runnable listener)
  {
    mDisplaySpeedListeners.addObserver(listener);
  }

  private void startVehicleSpeedSources()
  {
    mVehicleSpeedSource.start();
    mMcuSpeedSource.start();
    updateDisplaySpeed();
  }

  private void stopVehicleSpeedSources()
  {
    mVehicleSpeedSource.stop();
    mMcuSpeedSource.stop();
  }

  private void updateDisplaySpeed()
  {
    mHandler.removeCallbacks(mDisplaySpeedExpiry);
    long now = SystemClock.elapsedRealtimeNanos();
    boolean simulated = mLocationProvider instanceof RouteSimulationProvider
                     || (mRawLocation != null && LocationCompat.isMock(mRawLocation));
    mDisplaySpeed = mActive
                      ? selectDisplaySpeed(simulated ? null : mMcuSpeedSource.getMeasurement(),
                                           simulated ? null : mVehicleSpeedSource.getMeasurement(), mRawLocation, now)
                      : null;
    if (mDisplaySpeed != null)
    {
      long remaining = mDisplaySpeed.maxAgeNanos() - (now - mDisplaySpeed.timestampNanos());
      mHandler.postDelayed(mDisplaySpeedExpiry, remaining / 1_000_000L + 1);
    }
    for (Runnable listener : mDisplaySpeedListeners)
      listener.run();
  }

  @Nullable
  static DisplaySpeed selectDisplaySpeed(@Nullable VehicleSpeedCache.Sample mcu,
                                         @Nullable VehicleSpeedCache.Sample vehicle, @Nullable Location gnss, long now)
  {
    if (mcu != null)
    {
      DisplaySpeed speed =
          new DisplaySpeed(Math.abs(mcu.speed), "speedMCU", mcu.timestamp, VehicleSpeedCache.MAX_AGE_NS);
      if (speed.isFresh(now))
        return speed;
    }
    if (vehicle != null)
    {
      DisplaySpeed speed =
          new DisplaySpeed(Math.abs(vehicle.speed), "speed", vehicle.timestamp, VehicleSpeedCache.MAX_AGE_NS);
      if (speed.isFresh(now))
        return speed;
    }
    Double speed = selectCurrentSpeed(gnss, null, now);
    return speed == null ? null : new DisplaySpeed(speed, "gnss", gnss.getElapsedRealtimeNanos(), 5_000_000_000L);
  }

  /** Kinematic speed for motion consumers; display instruments use getDisplaySpeed() instead. */
  @Nullable
  @UiThread
  public Double getCurrentSpeedMetersPerSecond()
  {
    if (!mActive)
      return null;
    boolean simulated = mLocationProvider instanceof RouteSimulationProvider
                     || (mRawLocation != null && LocationCompat.isMock(mRawLocation));
    return selectCurrentSpeed(mRawLocation, simulated ? null : mVehicleSpeedSource.getSpeedMetersPerSecond(),
                              SystemClock.elapsedRealtimeNanos());
  }

  @Nullable
  static Double selectCurrentSpeed(@Nullable Location location, @Nullable Double vehicleSpeed, long nowNanos)
  {
    if (vehicleSpeed != null && Double.isFinite(vehicleSpeed))
      return Math.abs(vehicleSpeed);
    if (location == null || !location.hasSpeed())
      return null;
    long timestamp = location.getElapsedRealtimeNanos();
    double speed = location.getSpeed();
    if (timestamp <= 0 || timestamp > nowNanos || nowNanos - timestamp > 5_000_000_000L || !Double.isFinite(speed)
        || speed < 0 || speed > VehicleSpeedCache.MAX_ABS_SPEED_MPS)
      return null;
    return speed;
  }

  /**
   * Indicates about whether a location provider is polling location updates right now or not.
   */
  public boolean isActive()
  {
    return mActive;
  }

  private void notifyLocationUpdated()
  {
    if (mSavedLocation == null)
      throw new IllegalStateException("No saved location");

    mHandler.removeCallbacks(mLocationTimeoutRunnable);
    mHandler.postDelayed(mLocationTimeoutRunnable, LOCATION_UPDATE_TIMEOUT_MS); // Reset the timeout.

    mListenersIterator.rewind();
    while (mListenersIterator.hasNext())
      mListenersIterator.next().onLocationUpdated(mSavedLocation);

    // If we are still in the first run mode, i.e. user is staying on the first run screens,
    // not on the map, we mustn't post location update to the core. Only this preserving allows us
    // to play nice zoom animation once a user will leave first screens and will see a map.
    if (mInFirstRun)
    {
      Logger.d(TAG, "Location update is obtained and must be ignored, because the app is in a first run mode");
      return;
    }

    final LocationCompatExtractor.Altitude altitude = LocationCompatExtractor.getAltitude(mSavedLocation);
    long elapsed = mSavedLocation.getElapsedRealtimeNanos();
    double age = elapsed > 0 ? (SystemClock.elapsedRealtimeNanos() - elapsed) / 1_000_000_000.0 : Double.NaN;
    LocationState.nativeLocationUpdatedWithAge(
        mSavedLocation.getTime(), mSavedLocation.getLatitude(), mSavedLocation.getLongitude(),
        mSavedLocation.getAccuracy(), altitude != null ? altitude.altitude() : 0,
        altitude != null ? altitude.accuracy() : -1, mSavedLocation.hasSpeed() ? mSavedLocation.getSpeed() : -1,
        mSavedLocation.hasBearing() ? mSavedLocation.getBearing() : -1,
        LocationCompat.isMock(mSavedLocation) ? Double.NaN : age);
  }

  private void onVehicleSpeedChanged(double speedMps, long timestampNanos, boolean valid)
  {
    if (valid
        && (!mActive || mLocationProvider instanceof RouteSimulationProvider
            || (mSavedLocation != null && LocationCompat.isMock(mSavedLocation))))
      return;
    double age = valid ? (SystemClock.elapsedRealtimeNanos() - timestampNanos) / 1_000_000_000.0 : 0.0;
    LocationState.nativeVehicleSpeedUpdated(speedMps, age, valid);
    updateDisplaySpeed();
  }

  private void notifyLocationUpdateTimeout()
  {
    mHandler.removeCallbacks(mLocationTimeoutRunnable);
    if (!isActive())
    {
      Logger.w(TAG, "Provider is not active");
      return;
    }

    Logger.d(TAG);
    mListenersIterator.rewind();
    while (mListenersIterator.hasNext())
      mListenersIterator.next().onLocationUpdateTimeout();
  }

  @Override
  public void onLocationChanged(@NonNull Location location)
  {
    Logger.d(TAG, "provider = " + mLocationProvider.getClass().getSimpleName() + " location = " + location);

    if (!isActive())
    {
      Logger.w(TAG, "Provider is not active");
      return;
    }

    if (!LocationUtils.isAccuracySatisfied(location))
    {
      Logger.w(TAG, "Unsatisfied accuracy for location = " + location);
      return;
    }

    if (mSavedLocation != null)
    {
      if (!LocationUtils.isLocationBetterThanLast(location, mSavedLocation))
      {
        Logger.d(TAG, "The new " + location + " is worse than the last " + mSavedLocation);
        return;
      }
    }

    mRawLocation = new Location(location);
    mSavedLocation =
        mLocationProvider instanceof RouteSimulationProvider ? location : mVehicleSpeedSource.applyTo(location);
    if (LocationCompat.isMock(location))
      LocationState.nativeVehicleSpeedUpdated(0.0, 0.0, false);
    mMyPosition = null;
    updateDisplaySpeed();
    notifyLocationUpdated();
  }

  // Used by GoogleFusedLocationProvider.
  @SuppressWarnings("unused")
  @Override
  @UiThread
  public void onLocationResolutionRequired(@NonNull PendingIntent pendingIntent)
  {
    Logger.d(TAG);

    if (!isActive())
    {
      Logger.w(TAG, "Provider is not active");
      return;
    }

    // Stop provider until location resolution is granted.
    stop();
    LocationState.nativeOnLocationError(LocationState.ERROR_GPS_OFF);

    mListenersIterator.rewind();
    while (mListenersIterator.hasNext())
      mListenersIterator.next().onLocationResolutionRequired(pendingIntent);
  }

  // Used by GoogleFusedLocationProvider.
  @SuppressWarnings("unused")
  @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
  @Override
  @UiThread
  public void onFusedLocationUnsupported()
  {
    // Try to downgrade to the native provider first and restart the service before notifying the user.
    Logger.d(TAG, "provider = " + mLocationProvider.getClass().getSimpleName() + " is not supported,"
                      + " downgrading to use native provider");
    mLocationProvider.stop();
    mLocationProvider = new AndroidNativeProvider(mContext, this);
    mActive = true;
    mLocationProvider.start(mInterval);
  }

  // RouteSimulationProvider doesn't really require location permissions.
  @SuppressLint("MissingPermission")
  public void startNavigationSimulation(JunctionInfo[] points)
  {
    stopVehicleSpeedSources();
    Logger.i(TAG);
    mOldLocationProvider = mLocationProvider;
    mLocationProvider.stop();
    mLocationProvider = new RouteSimulationProvider(mContext, this, points);
    mActive = true;
    mLocationProvider.start(mInterval);
  }

  @SuppressLint("MissingPermission")
  public void stopNavigationSimulation()
  {
    Logger.i(TAG);
    mLocationProvider.stop();
    if (mOldLocationProvider == null)
      throw new IllegalStateException("Should be called only after startNavigationSimulation()");
    mLocationProvider = mOldLocationProvider;
    startVehicleSpeedSources();
    mActive = true;
    mLocationProvider.start(mInterval);
  }

  @Override
  @UiThread
  public void onLocationDisabled()
  {
    Logger.d(TAG, "provider = " + mLocationProvider.getClass().getSimpleName()
                      + " settings = " + LocationUtils.areLocationServicesTurnedOn(mContext));

    stop();
    LocationState.nativeOnLocationError(LocationState.ERROR_GPS_OFF);

    mListenersIterator.rewind();
    while (mListenersIterator.hasNext())
      mListenersIterator.next().onLocationDisabled();
  }

  /**
   * Registers listener to obtain location updates.
   *
   * @param listener    listener to be registered.
   */
  @UiThread
  public void addListener(@NonNull LocationListener listener)
  {
    Logger.d(TAG, "listener: " + listener + " count was: " + mListeners.size());

    mListeners.addObserver(listener);
    if (mSavedLocation != null)
      listener.onLocationUpdated(mSavedLocation);
  }

  /**
   * Removes given location listener.
   * @param listener listener to unregister.
   */
  @UiThread
  public void removeListener(@NonNull LocationListener listener)
  {
    Logger.d(TAG, "listener: " + listener + " count was: " + mListeners.size());
    mListeners.removeObserver(listener);
  }

  private long calcLocationUpdatesInterval()
  {
    if (RoutingController.get().isNavigating() || hasExternalNavigation())
      return INTERVAL_NAVIGATION_MS;

    if (TrackRecorder.nativeIsTrackRecordingEnabled())
      return INTERVAL_TRACK_RECORDING;

    final int mode = Map.isEngineCreated() ? LocationState.getMode() : LocationState.NOT_FOLLOW_NO_POSITION;
    return switch (mode)
    {
      case LocationState.PENDING_POSITION, LocationState.FOLLOW, LocationState.FOLLOW_AND_ROTATE -> INTERVAL_FOLLOW_MS;
      case LocationState.NOT_FOLLOW, LocationState.NOT_FOLLOW_NO_POSITION -> INTERVAL_NOT_FOLLOW_MS;
      default -> throw new IllegalArgumentException("Unsupported location mode: " + mode);
    };
  }

  /**
   * Restart the location with a new refresh interval if changed.
   */
  @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
  public void restartWithNewMode()
  {
    if (!isActive())
    {
      start();
      return;
    }

    final long newInterval = calcLocationUpdatesInterval();
    if (newInterval == mInterval)
      return;

    Logger.i(TAG, "update refresh interval: old = " + mInterval + " new = " + newInterval);
    mLocationProvider.stop();
    mInterval = newInterval;
    mLocationProvider.start(newInterval);
  }

  /**
   * Starts polling location updates.
   */
  @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
  public void start()
  {
    if (isActive())
    {
      Logger.d(TAG, "Already started");
      if (!(mLocationProvider instanceof RouteSimulationProvider))
        startVehicleSpeedSources();
      return;
    }

    Logger.i(TAG);
    checkForAgpsUpdates();

    if (LocationUtils.checkFineLocationPermission(mContext))
      mSensorHelper.start();

    final long oldInterval = mInterval;
    mInterval = calcLocationUpdatesInterval();
    Logger.i(TAG, "provider = " + mLocationProvider.getClass().getSimpleName() + " mInFirstRun = " + mInFirstRun
                      + " oldInterval = " + oldInterval + " interval = " + mInterval);
    mActive = true;
    startVehicleSpeedSources();
    mLocationProvider.start(mInterval);
    mHandler.postDelayed(mLocationTimeoutRunnable, LOCATION_UPDATE_TIMEOUT_MS);
    subscribeToGnssStatusUpdates();
  }

  /**
   * Stops the polling location updates.
   */
  public void stop()
  {
    if (!isActive())
    {
      Logger.d(TAG, "Already stopped");
      return;
    }

    Logger.i(TAG);
    stopVehicleSpeedSources();
    mLocationProvider.stop();
    unsubscribeFromGnssStatusUpdates();
    mSensorHelper.stop();
    mHandler.removeCallbacks(mLocationTimeoutRunnable);
    mActive = false;
    updateDisplaySpeed();
  }

  /**
   * Resume location services when entering the foreground.
   */
  public void resumeLocationInForeground()
  {
    if (isActive())
    {
      if (!(mLocationProvider instanceof RouteSimulationProvider))
        startVehicleSpeedSources();
      return;
    }
    else if (!Map.isEngineCreated())
    {
      // LocationState.nativeGetMode() is initialized only after drape creation.
      // https://github.com/organicmaps/organicmaps/issues/1128#issuecomment-1784435190
      Logger.d(TAG, "Engine is not created yet.");
      return;
    }
    else if (LocationState.getMode() == LocationState.NOT_FOLLOW_NO_POSITION)
    {
      Logger.i(TAG, "Location updates are stopped by the user manually.");
      return;
    }
    else if (!LocationUtils.checkLocationPermission(mContext))
    {
      Logger.i(TAG, "Permissions ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION are not granted");
      return;
    }

    start();
  }

  private void checkForAgpsUpdates()
  {
    if (!NetworkPolicy.getCurrentNetworkUsageStatus())
      return;

    long previousTimestamp = Config.getAgpsTimestamp();
    long currentTimestamp = System.currentTimeMillis();
    if (previousTimestamp + AGPS_EXPIRATION_TIME_MS > currentTimestamp)
    {
      Logger.d(TAG, "A-GPS should be up to date");
      return;
    }

    Logger.d(TAG, "Requesting new A-GPS data");
    Config.setAgpsTimestamp(currentTimestamp);
    final LocationManager manager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
    manager.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_xtra_injection", null);
    manager.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_time_injection", null);
  }

  private void subscribeToGnssStatusUpdates()
  {
    // Subscribe to the low-level GNSS status to keep the green dot location indicator always firing.
    // https://github.com/organicmaps/organicmaps/issues/5999#issuecomment-1793713369
    if (!LocationUtils.checkFineLocationPermission(mContext))
      return;
    final LocationManager locationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
    LocationManagerCompat.registerGnssStatusCallback(locationManager, ContextCompat.getMainExecutor(mContext),
                                                     mGnssStatusCallback);
  }

  private void unsubscribeFromGnssStatusUpdates()
  {
    final LocationManager locationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
    LocationManagerCompat.unregisterGnssStatusCallback(locationManager, mGnssStatusCallback);
  }

  @UiThread
  public boolean isInFirstRun()
  {
    return mInFirstRun;
  }

  @UiThread
  public void onEnteredIntoFirstRun()
  {
    Logger.i(TAG);
    mInFirstRun = true;
  }

  @UiThread
  public void onExitFromFirstRun()
  {
    Logger.i(TAG);
    if (!mInFirstRun)
      throw new AssertionError("Must be called only after 'onEnteredIntoFirstRun' method!");

    mInFirstRun = false;

    // If there is a location we need just to pass it to the listeners, so that
    // my position state machine will be switched to the FOLLOW state.
    if (mSavedLocation != null)
    {
      notifyLocationUpdated();
      Logger.d(TAG, "Current location is available, so play the nice zoom animation");
      Framework.nativeRunFirstLaunchAnimation();
    }
  }

  public boolean isGmsLocationProviderAvailable()
  {
    return mLocationProviderFactory.isGmsLocationProviderAvailable(mContext);
  }
}
