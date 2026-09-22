package app.organicmaps.routing;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.cluster.NavigationProvider;
import app.organicmaps.sdk.sound.NavigationWarningPlayer;
import app.organicmaps.sdk.sound.TtsPlayer;
import app.organicmaps.settings.SpeedWarningSettings;

/** A single application owner handles alerts even with several maps or only a cluster visible. */
public final class SpeedWarningController
{
  private static final String SOUND_ASSET = "overspeed_warning.mp3";
  private final MwmApplication mApp;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private final SpeedWarningState mState = new SpeedWarningState();
  private final NavigationWarningPlayer mPlayer;
  private final Runnable mCheck = this::update;
  // SharedPreferences holds weak references, so retain this application-lifetime listener.
  private final SharedPreferences.OnSharedPreferenceChangeListener mSettingsListener;
  private int mOffsetKmh;
  private String mMode;

  public SpeedWarningController(MwmApplication app)
  {
    mApp = app;
    mPlayer = new NavigationWarningPlayer(app);
    mSettingsListener = (prefs, key) ->
    {
      if (SpeedWarningSettings.OFFSET_KEY.equals(key) || SpeedWarningSettings.MODE_KEY.equals(key))
      {
        readSettings();
        mPlayer.stop();
        update();
      }
    };
    MwmApplication.prefs(app).registerOnSharedPreferenceChangeListener(mSettingsListener);
    readSettings();
  }

  private void readSettings()
  {
    mOffsetKmh = SpeedWarningSettings.offsetKmh(mApp);
    mMode = SpeedWarningSettings.mode(mApp);
  }

  public static boolean isExceeded(Context context, double speedMps, double limitMps)
  {
    return SpeedWarningState.isExceeded(speedMps, limitMps, SpeedWarningSettings.offsetKmh(context));
  }

  public void update()
  {
    mHandler.removeCallbacks(mCheck);
    var reading = mApp.getLocationHelper().getDisplaySpeed();
    double speed = reading == null ? Double.NaN : reading.speedMps();
    double limit = NavigationProvider.getCurrentSpeedLimitMps();
    long now = SystemClock.elapsedRealtime();
    boolean due = mState.update(speed, limit, mOffsetKmh, now);
    if (due)
    {
      boolean played = switch (mMode)
      {
        case SpeedWarningSettings.VOICE ->
          TtsPlayer.INSTANCE.speakWarning(mApp.getString(R.string.auto_speed_warning_voice));
        case SpeedWarningSettings.SOUND -> mPlayer.play(SOUND_ASSET);
        default -> false;
      };
      if (played)
        mState.notified(now);
    }
    // Check freshness before a delayed alert; never queue speech based on an expired GNSS/MCU sample.
    if (SpeedWarningState.isExceeded(speed, limit, mOffsetKmh))
      mHandler.postDelayed(mCheck, 500);
  }
}
