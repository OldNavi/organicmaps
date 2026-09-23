package app.organicmaps.road;

import androidx.annotation.StringRes;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.sound.NavigationWarningPlayer;
import app.organicmaps.sdk.sound.TtsPlayer;
import app.organicmaps.settings.SpeedWarningSettings;

/** Road events and overspeed share one navigation-channel player and cannot overlap. */
public final class RoadWarningAudio
{
  private final MwmApplication mApp;
  private final NavigationWarningPlayer mPlayer;
  private long mLastStarted = -1;

  public RoadWarningAudio(MwmApplication app)
  {
    mApp = app;
    mPlayer = new NavigationWarningPlayer(app);
  }

  public boolean play(@StringRes int message, long now)
  {
    return play(mApp.getString(message), now);
  }

  public boolean play(String message, long now)
  {
    if (SpeedWarningSettings.level(mApp) == SpeedWarningSettings.OFF
        || (mLastStarted >= 0 && now - mLastStarted < 3000))
      return false;
    boolean played = SpeedWarningSettings.SOUND.equals(SpeedWarningSettings.mode(mApp))
                       ? mPlayer.play("overspeed_warning.mp3")
                       : TtsPlayer.INSTANCE.speakWarning(message);
    if (played)
      mLastStarted = now;
    return played;
  }

  public void stop()
  {
    mPlayer.stop();
  }
}
