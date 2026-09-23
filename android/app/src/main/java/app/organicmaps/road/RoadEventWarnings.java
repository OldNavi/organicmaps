package app.organicmaps.road;

import android.os.SystemClock;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.sdk.cluster.RoadInfo;
import app.organicmaps.sdk.road.RoadEventAhead;
import app.organicmaps.sdk.road.RoadEventKind;
import app.organicmaps.sdk.util.StringUtils;
import app.organicmaps.settings.SpeedWarningSettings;

/** One warning owner for imported events and built-in cameras, independent of the active map surface. */
public final class RoadEventWarnings
{
  private final MwmApplication mApp;
  private final RoadWarningAudio mAudio;
  private final RoadEventWarningPolicy mPolicy = new RoadEventWarningPolicy();

  public RoadEventWarnings(MwmApplication app, RoadWarningAudio audio)
  {
    mApp = app;
    mAudio = audio;
  }

  public void update(RoadInfo road, double[] camera)
  {
    var manager = RoadDataManager.get(mApp);
    if (SpeedWarningSettings.level(mApp) == SpeedWarningSettings.OFF)
      return;
    var reading = mApp.getLocationHelper().getDisplaySpeed();
    double speed = reading == null ? Double.NaN : reading.speedMps();
    int visible = manager.visibleKinds();
    int level = SpeedWarningSettings.level(mApp);
    long now = SystemClock.elapsedRealtime();
    mPolicy.expire(now);
    if (manager.enabled())
      for (var event : road.warnings)
        mPolicy.observe(event, now);
    RoadEventAhead nativeCamera =
        camera.length == 5 && camera[0] >= 0 && camera[0] <= Math.max(150, speed * 15)
            ? new RoadEventAhead("", RoadEventKind.CAMERA, camera[0], camera[1], camera[2], camera[3])
            : null;
    if (nativeCamera != null)
      mPolicy.observe(nativeCamera, now);
    // Imported data is more specific. Recording its announcement also suppresses a colocated native camera.
    if (manager.enabled())
      for (var event : road.warnings)
        if (announce(event, level, visible, speed, now))
          return;
    if (nativeCamera != null)
      announce(nativeCamera, level, visible, speed, now);
  }

  private boolean announce(RoadEventAhead event, int level, int visible, double speed, long now)
  {
    if (!RoadEventWarningPolicy.isEligible(event, level, visible, speed, SpeedWarningSettings.offsetKmh(mApp))
        || mPolicy.wasAnnounced(event))
      return false;
    if (!mAudio.play(message(event), now))
      return false;
    mPolicy.announced(event, now);
    return true;
  }

  private String message(RoadEventAhead event)
  {
    int resource = switch (event.kind())
    {
      case RoadEventKind.BUMP -> R.string.road_warning_bump;
      case RoadEventKind.CHILDREN -> R.string.road_warning_children;
      case RoadEventKind.LANE_CONTROL -> R.string.road_warning_lane_camera;
      case RoadEventKind.RED_LIGHT -> R.string.road_warning_cross_camera;
      case RoadEventKind.CAMERA -> R.string.road_warning_speed_camera;
      default -> 0;
    };
    if (resource != 0)
      return mApp.getString(resource);
    if (event.kind() == RoadEventKind.SPEED_LIMIT && event.speedMps() > 0)
    {
      var value = StringUtils.nativeFormatSpeedAndUnits(event.speedMps());
      return mApp.getString(R.string.road_warning_speed_limit, value.first + " " + value.second);
    }
    return mApp.getString(R.string.road_event_announcement, RoadEventLabels.name(mApp, event.kind()));
  }
}
