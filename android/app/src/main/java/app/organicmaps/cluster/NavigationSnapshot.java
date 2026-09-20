package app.organicmaps.cluster;

import androidx.annotation.Nullable;
import app.organicmaps.sdk.cluster.RoadInfo;
import app.organicmaps.sdk.routing.CarDirection;
import app.organicmaps.sdk.routing.LaneWay;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.util.Distance;

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

  static double speedLimitMps(double limitMps)
  {
    return Math.max(0.0, limitMps);
  }

  Object[] cameraRow()
  {
    if (camera.length != 5)
      return new Object[] {Integer.MAX_VALUE, 0.0, "m", "", 0};
    return new Object[] {(int) Math.round(camera[0]), camera[1], "m",
                         String.format(java.util.Locale.ROOT, "%.6f,%.6f", camera[2], camera[3]), (int) camera[4]};
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
