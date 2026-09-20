package app.organicmaps.cluster;

import static org.junit.Assert.*;

import app.organicmaps.sdk.routing.CarDirection;
import app.organicmaps.sdk.routing.LaneWay;
import app.organicmaps.sdk.util.Distance;
import java.util.Set;
import org.junit.Test;

public class NavigationSnapshotTest
{
  @Test
  public void ordinaryDrivingKeepsRouteEmptyAndTracksOriginalFixAge()
  {
    var road =
        new app.organicmaps.sdk.cluster.RoadInfo(true, 50.0 / 3.6, "Road", new double[] {100, 40.0 / 3.6, 55, 37, 1});
    var snapshot = new NavigationSnapshot(null, road.camera, new double[3], road, 10_000_000_000L);
    assertNull(snapshot.info);
    assertEquals(50.0 / 3.6, snapshot.roadInfo.speedLimitMps, 0.001);
    assertEquals(6000, snapshot.fixAgeMillis(16_000_000_000L));
    assertEquals(Long.MAX_VALUE, snapshot.fixAgeMillis(9_000_000_000L));
    assertEquals(Long.MAX_VALUE, NavigationSnapshot.EMPTY.fixAgeMillis(16_000_000_000L));
    assertEquals(100, snapshot.cameraRow()[0]);
  }
  @Test
  public void allManeuversMatchThePremiumProtocol()
  {
    Set<String> accepted =
        Set.of("unknown", "straight", "slight_left", "slight_right", "left", "right", "hard_left", "hard_right",
               "fork_left", "fork_right", "uturn_left", "uturn_right", "enter_roundabout", "leave_roundabout",
               "board_ferry", "leave_ferry", "exit_left", "exit_right", "finish", "waypoint");
    for (CarDirection direction : CarDirection.values())
      assertTrue(direction.name(), accepted.contains(NavigationSnapshot.action(direction)));
    assertEquals("exit_left", NavigationSnapshot.action(CarDirection.ExitHighwayToLeft));
    assertEquals("uturn_right", NavigationSnapshot.action(CarDirection.UTurnRight));
    assertEquals("leave_roundabout", NavigationSnapshot.action(CarDirection.LeaveRoundAbout));
  }

  @Test
  public void laneDirectionsAndInactiveLaneAreRepresentable()
  {
    assertEquals("", NavigationSnapshot.lane(LaneWay.None));
    assertEquals("straight_ahead", NavigationSnapshot.lane(LaneWay.Through));
    assertEquals("left180", NavigationSnapshot.lane(LaneWay.ReverseLeft));
    assertEquals("right_shift", NavigationSnapshot.lane(LaneWay.MergeToRight));
  }

  @Test
  public void formattedDistancesKeepImperialUnitsSeparateFromMeters()
  {
    Distance miles = new Distance(2.0, "2", (byte) 3);
    assertEquals("mi", NavigationSnapshot.unit(miles));
    assertEquals(3219, NavigationSnapshot.meters(miles));
    assertEquals(1500, NavigationSnapshot.meters(new Distance(1.5, "1.5", (byte) 1)));
    assertEquals(305, NavigationSnapshot.meters(new Distance(1000, "1000", (byte) 2)));
  }

  @Test
  public void speedLimitsUseMetersPerSecondAndMissingCameraCannotLookLikeOneAtZeroMeters()
  {
    assertEquals(50.0, NavigationSnapshot.speedLimitMps(50.0 / 3.6) * 3.6, 0.001);
    assertEquals(0.0, NavigationSnapshot.speedLimitMps(-1.0), 0.0);
    Object[] empty = NavigationSnapshot.EMPTY.cameraRow();
    assertEquals(Integer.MAX_VALUE, empty[0]);
    assertEquals("", empty[3]);
    var camera = new NavigationSnapshot(null, new double[] {120, 60.0 / 3.6, 55, 37, 1}, new double[3]);
    assertEquals(60.0, ((Double) camera.cameraRow()[1]) * 3.6, 0.001);
  }

  @Test
  public void snapshotDoesNotRetainMutableNativeArrays()
  {
    double[] camera = {500, 60, 55, 37, 0};
    double[] route = {1500, 3000, 100};
    NavigationSnapshot snapshot = new NavigationSnapshot(null, camera, route);
    camera[0] = 0;
    route[0] = 0;
    assertEquals(500, snapshot.camera[0], 0);
    assertEquals(1500, snapshot.metrics[0], 0);
    assertNull(NavigationSnapshot.EMPTY.info);
    assertEquals(0, NavigationSnapshot.EMPTY.camera.length);
  }
}
