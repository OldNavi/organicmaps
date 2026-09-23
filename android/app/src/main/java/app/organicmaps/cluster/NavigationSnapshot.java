package app.organicmaps.cluster;

import androidx.annotation.Nullable;
import app.organicmaps.sdk.cluster.RoadInfo;
import app.organicmaps.sdk.routing.CarDirection;
import app.organicmaps.sdk.routing.LaneInfo;
import app.organicmaps.sdk.routing.LaneWay;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.routing.roadshield.RoadShield;
import app.organicmaps.sdk.util.Distance;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

final class NavigationSnapshot
{
  static final NavigationSnapshot EMPTY = new NavigationSnapshot(null, new double[0], new double[3]);
  @Nullable
  final RoutingInfo info;
  final double[] camera;
  final double[] metrics;
  final RoadInfo roadInfo;
  final long fixTimeNanos;

  NavigationSnapshot(@Nullable RoutingInfo routingInfo, double[] cameraInfo, double[] routeMetrics)
  {
    this(routingInfo, cameraInfo, routeMetrics, RoadInfo.EMPTY, 0);
  }

  NavigationSnapshot(@Nullable RoutingInfo routingInfo, double[] cameraInfo, double[] routeMetrics, RoadInfo roadInfo,
                     long fixTimeNanos)
  {
    info = routingInfo;
    camera = cameraInfo.clone();
    metrics = routeMetrics.clone();
    this.roadInfo = roadInfo;
    this.fixTimeNanos = fixTimeNanos;
  }

  long fixAgeMillis(long nowNanos)
  {
    return fixTimeNanos <= 0 || fixTimeNanos > nowNanos ? Long.MAX_VALUE : (nowNanos - fixTimeNanos) / 1_000_000;
  }

  NavigationSnapshot validAt(long nowNanos)
  {
    return fixAgeMillis(nowNanos) > RoadInfoMonitor.MAX_FIX_AGE_MS ? EMPTY : this;
  }

  /** Compare the fields exposed by each URI, without building cursors or serialized rows. */
  boolean hasSameData(String path, NavigationSnapshot previous)
  {
    return switch (path)
    {
      case "road_events" ->
        Objects.equals(roadInfo.eventId, previous.roadInfo.eventId)
            && Arrays.equals(roadInfo.event, previous.roadInfo.event);
      case "guidance" -> sameGuidance(previous);
      case "speed_camera" -> sameCamera(previous);
      case "maneuver" ->
        info == null || previous.info == null
            ? info == previous.info
            : sameTurnDistance(previous) && action(info.carDirection).equals(action(previous.info.carDirection))
                  && Objects.equals(info.nextStreet, previous.info.nextStreet) && info.exitNum == previous.info.exitNum;
      case "lanes" -> sameLanes(previous);
      case "direction_signs" -> sameSigns(previous);
      case "routes" ->
        info == null || previous.info == null ? info == previous.info
                                              : Math.round(metrics[1]) == Math.round(previous.metrics[1])
                                                    && info.totalTimeInSeconds == previous.info.totalTimeInSeconds;
      default -> throw new IllegalArgumentException("Unknown navigation data: " + path);
    };
  }

  private boolean sameGuidance(NavigationSnapshot previous)
  {
    // A new fix renews validity even when the limit is unchanged. ISA consumes these updates too.
    if (fixTimeNanos != previous.fixTimeNanos
        || roadInfo.externalSpeedLimitMps != previous.roadInfo.externalSpeedLimitMps)
      return false;
    if (info == null || previous.info == null)
      return info == previous.info && roadInfo.matched == previous.roadInfo.matched
   && Double.compare(roadInfo.speedLimitMps, previous.roadInfo.speedLimitMps) == 0
   && Objects.equals(roadInfo.road, previous.roadInfo.road);
    return Double.compare(speedLimitMps(info.speedLimitMps), speedLimitMps(previous.info.speedLimitMps)) == 0
 && Math.round(metrics[0]) == Math.round(previous.metrics[0])
 && Math.round(metrics[1]) == Math.round(previous.metrics[1])
 && sameDisplayDistance(info.distToTarget, previous.info.distToTarget)
 && info.totalTimeInSeconds == previous.info.totalTimeInSeconds
 && Objects.equals(info.currentStreet, previous.info.currentStreet);
  }

  private boolean sameCamera(NavigationSnapshot previous)
  {
    if (camera.length != 5 || previous.camera.length != 5)
      return camera.length != 5 && previous.camera.length != 5;
    // Camera passage tracking needs fresh observations even while the vehicle is stationary.
    return fixTimeNanos == previous.fixTimeNanos && Arrays.equals(camera, previous.camera);
  }

  private boolean sameTurnDistance(NavigationSnapshot previous)
  {
    return Math.round(metrics[2]) == Math.round(previous.metrics[2])
 && sameDisplayDistance(info.distToTurn, previous.info.distToTurn);
  }

  private static boolean sameDisplayDistance(Distance a, Distance b)
  {
    return a.mUnits == b.mUnits && Objects.equals(a.mDistanceStr, b.mDistanceStr);
  }

  private boolean sameLanes(NavigationSnapshot previous)
  {
    LaneInfo[] lanes = info == null ? null : info.lanes;
    LaneInfo[] oldLanes = previous.info == null ? null : previous.info.lanes;
    int count = lanes == null ? 0 : lanes.length;
    if (count != (oldLanes == null ? 0 : oldLanes.length))
      return false;
    if (count == 0)
      return true;
    // Distance is part of the public lanes contract, even when its artwork stays the same.
    if (!sameTurnDistance(previous))
      return false;
    for (int i = 0; i < count; ++i)
      if (lanes[i].mActiveLaneWay != oldLanes[i].mActiveLaneWay
          || !Arrays.equals(lanes[i].mLaneWays, oldLanes[i].mLaneWays))
        return false;
    return true;
  }

  private RoadShield[] roadShields()
  {
    return info != null && info.nextStreetRoadShields != null && info.nextStreetRoadShields.hasTargetRoadShields()
      ? info.nextStreetRoadShields.targetRoadShields
      : null;
  }

  private boolean sameSigns(NavigationSnapshot previous)
  {
    RoadShield[] shields = roadShields();
    RoadShield[] oldShields = previous.roadShields();
    int count = shields == null ? 0 : shields.length;
    if (count != (oldShields == null ? 0 : oldShields.length))
      return false;
    if (count == 0)
      return true;
    if (!sameTurnDistance(previous) || !action(info.carDirection).equals(action(previous.info.carDirection)))
      return false;
    for (int i = 0; i < count; ++i)
      if (!Objects.equals(shields[i].text, oldShields[i].text))
        return false;
    return true;
  }

  static double speedLimitMps(double limitMps)
  {
    return Math.max(0.0, limitMps);
  }

  double currentSpeedLimitMps(long nowNanos)
  {
    if (fixAgeMillis(nowNanos) > RoadInfoMonitor.MAX_FIX_AGE_MS)
      return 0.0;
    double limit = roadInfo.externalSpeedLimitMps > 0 ? roadInfo.externalSpeedLimitMps
                 : info != null                       ? info.speedLimitMps
                 : roadInfo.matched                   ? roadInfo.speedLimitMps
                                                      : 0.0;
    return Double.isFinite(limit) ? speedLimitMps(limit) : 0.0;
  }

  Object[] cameraRow()
  {
    if (camera.length != 5)
      return new Object[] {Integer.MAX_VALUE, 0.0, "m", "", 0};
    return new Object[] {(int) Math.round(camera[0]), camera[1], "m",
                         String.format(Locale.ROOT, "%.6f,%.6f", camera[2], camera[3]), (int) camera[4]};
  }

  static String action(CarDirection direction)
  {
    return switch (direction)
    {
      case NoTurn -> "unknown";
      case GoStraight, StartAtEndOfStreet, StayOnRoundAbout -> "straight";
      case TurnRight -> "right";
      case TurnSharpRight -> "hard_right";
      case TurnSlightRight -> "slight_right";
      case TurnLeft -> "left";
      case TurnSharpLeft -> "hard_left";
      case TurnSlightLeft -> "slight_left";
      case UTurnLeft -> "uturn_left";
      case UTurnRight -> "uturn_right";
      case EnterRoundAbout -> "enter_roundabout";
      case LeaveRoundAbout -> "leave_roundabout";
      case ReachedYourDestination -> "finish";
      case ExitHighwayToLeft -> "exit_left";
      case ExitHighwayToRight -> "exit_right";
    };
  }

  static String lane(LaneWay way)
  {
    return switch (way)
    {
      case None -> "";
      case ReverseLeft -> "left180";
      case SharpLeft -> "left135";
      case Left -> "left90";
      case SlightLeft -> "left45";
      case MergeToLeft -> "left_shift";
      case Through -> "straight_ahead";
      case SlightRight -> "right45";
      case MergeToRight -> "right_shift";
      case Right -> "right90";
      case SharpRight -> "right135";
      case ReverseRight -> "right180";
    };
  }

  static String unit(Distance distance)
  {
    return switch (distance.mUnits)
    {
      case Meters -> "m";
      case Kilometers -> "km";
      case Feet -> "ft";
      case Miles -> "mi";
    };
  }

  static int meters(Distance distance)
  {
    double factor = switch (distance.mUnits)
    {
      case Meters -> 1.0;
      case Kilometers -> 1000.0;
      case Feet -> 0.3048;
      case Miles -> 1609.344;
    };
    return (int) Math.round(distance.mDistance * factor);
  }
}
