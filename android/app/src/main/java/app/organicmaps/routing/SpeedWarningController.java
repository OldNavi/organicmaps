package app.organicmaps.routing;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.cluster.NavigationProvider;
import app.organicmaps.road.RoadWarningAudio;
import app.organicmaps.settings.SpeedWarningSettings;

/** A single application owner handles alerts even with several maps or only a cluster visible. */
public final class SpeedWarningController
{
  private final MwmApplication mApp;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private final SpeedWarningState mState = new SpeedWarningState();
  private final RoadWarningAudio mAudio;
  private final Runnable mCheck = this::update;
  // SharedPreferences holds weak references, so retain this application-lifetime listener.
  private final SharedPreferences.OnSharedPreferenceChangeListener mSettingsListener;
  private int mOffsetKmh;

  public SpeedWarningController(MwmApplication app, RoadWarningAudio audio)
  {
    mApp = app;
    mAudio = audio;
    mSettingsListener = (prefs, key) ->
    {
      if (SpeedWarningSettings.OFFSET_KEY.equals(key) || SpeedWarningSettings.MODE_KEY.equals(key)
          || SpeedWarningSettings.LEVEL_KEY.equals(key))
      {
        readSettings();
        mAudio.stop();
        update();
      }
    };
    MwmApplication.prefs(app).registerOnSharedPreferenceChangeListener(mSettingsListener);
    readSettings();
  }

  private void readSettings()
  {
    mOffsetKmh = SpeedWarningSettings.offsetKmh(mApp);
  }

  public static boolean isExceeded(Context context, double speedMps, double limitMps)
  {
    return SpeedWarningState.isExceeded(speedMps, limitMps, SpeedWarningSettings.offsetKmh(context));
  }

  public void update()
  {
    mHandler.removeCallbacks(mCheck);
    if (SpeedWarningSettings.level(mApp) == SpeedWarningSettings.OFF)
      return;
    var reading = mApp.getLocationHelper().getDisplaySpeed();
    double speed = reading == null ? Double.NaN : reading.speedMps();
    double limit = NavigationProvider.getCurrentSpeedLimitMps();
    long now = SystemClock.elapsedRealtime();
    boolean due = mState.update(speed, limit, mOffsetKmh, now);
    if (due)
    {
      boolean played = mAudio.play(R.string.auto_speed_warning_voice, now);
      if (played)
        mState.notified(now);
    }
    // Check freshness before a delayed alert; never queue speech based on an expired GNSS/MCU sample.
    if (SpeedWarningState.isExceeded(speed, limit, mOffsetKmh))
      mHandler.postDelayed(mCheck, 500);
  }
}
