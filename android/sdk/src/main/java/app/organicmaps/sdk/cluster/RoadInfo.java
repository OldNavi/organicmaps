package app.organicmaps.sdk.cluster;

import androidx.annotation.Keep;
import androidx.annotation.WorkerThread;
import app.organicmaps.sdk.road.RoadEventAhead;

@Keep
public final class RoadInfo
{
  public static final RoadInfo EMPTY = new RoadInfo(false, 0, "", new double[0]);
  public final boolean matched;
  public final double speedLimitMps;
  public final String road;
  public final double[] camera;
  public final double externalSpeedLimitMps;
  public final String eventId;
  public final double[] event;
  public final RoadEventAhead[] warnings;

  public RoadInfo(boolean matched, double speedLimitMps, String road, double[] camera)
  {
    this(matched, speedLimitMps, road, camera, 0, "", new double[0]);
  }

  public RoadInfo(boolean matched, double speedLimitMps, String road, double[] camera, double externalSpeedLimitMps,
                  String eventId, double[] event)
  {
    this(matched, speedLimitMps, road, camera, externalSpeedLimitMps, eventId, event, new RoadEventAhead[0]);
  }

  public RoadInfo(boolean matched, double speedLimitMps, String road, double[] camera, double externalSpeedLimitMps,
                  String eventId, double[] event, RoadEventAhead[] warnings)
  {
    this.warnings = warnings.clone();
    this.externalSpeedLimitMps = externalSpeedLimitMps;
    this.eventId = eventId;
    this.event = event.clone();
    this.matched = matched;
    this.speedLimitMps = speedLimitMps;
    this.road = road;
    this.camera = camera.clone();
  }

  /** Call on one persistent worker after native initialization; timestamp is Unix seconds. */
  @WorkerThread
  public static RoadInfo read(double latitude, double longitude, double accuracy, double speed, double bearing,
                              double timestamp)
  {
    if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || !Double.isFinite(accuracy)
        || !Double.isFinite(speed) || !Double.isFinite(bearing) || !Double.isFinite(timestamp)
        || Math.abs(latitude) > 85 || Math.abs(longitude) > 180 || accuracy <= 0 || accuracy > 30)
      return EMPTY;
    return nativeRead(latitude, longitude, accuracy, speed, bearing, timestamp);
  }

  private static native RoadInfo nativeRead(double latitude, double longitude, double accuracy, double speed,
                                            double bearing, double timestamp);
}
