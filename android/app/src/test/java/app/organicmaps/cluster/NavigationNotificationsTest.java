package app.organicmaps.cluster;

import static org.junit.Assert.*;

import app.organicmaps.sdk.cluster.RoadInfo;
import app.organicmaps.sdk.routing.CarDirection;
import app.organicmaps.sdk.routing.LaneInfo;
import app.organicmaps.sdk.routing.LaneWay;
import app.organicmaps.sdk.routing.PedestrianDirection;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.routing.roadshield.RoadShield;
import app.organicmaps.sdk.routing.roadshield.RoadShieldInfo;
import app.organicmaps.sdk.routing.roadshield.RoadShieldType;
import app.organicmaps.sdk.util.Distance;
import org.junit.Test;

public class NavigationNotificationsTest
{
  private static final long FIX = 10_000_000_000L;
  private static final String[] PATHS = {"guidance", "maneuver", "lanes", "speed_camera", "direction_signs", "routes"};

  private static final class Data
  {
    long fix = FIX;
    double turnMeters = 100;
    double remainingMeters = 1500;
    double totalMeters = 3000;
    int eta = 600;
    double limit = 15;
    String street = "Road";
    String nextStreet = "Next";
    int exit;
    CarDirection direction = CarDirection.TurnLeft;
    Distance turn = new Distance(100, "100", (byte) 0);
    LaneInfo[] lanes = {lane(LaneWay.Left, LaneWay.Left), lane(LaneWay.Through, LaneWay.None)};
    RoadShieldInfo shields;
    double[] camera = new double[0];

    NavigationSnapshot snapshot() throws Exception
    {
      var constructor = RoutingInfo.class.getDeclaredConstructors()[0];
      constructor.setAccessible(true);
      RoutingInfo info = (RoutingInfo) constructor.newInstance(
          new Distance(1.5, "1.5", (byte) 1), turn, street, nextStreet, shields, "", null, 0.0, direction,
          CarDirection.NoTurn, PedestrianDirection.values()[0], exit, eta, lanes, limit, false, false);
      return new NavigationSnapshot(info, camera, new double[] {remainingMeters, totalMeters, turnMeters},
                                    RoadInfo.EMPTY, fix);
    }
  }

  private static LaneInfo lane(LaneWay way, LaneWay active)
  {
    return new LaneInfo(new LaneWay[] {way}, active);
  }

  private static RoadShieldInfo shields(String... texts) throws Exception
  {
    return shields(RoadShieldType.GenericWhite, texts);
  }

  private static RoadShieldInfo shields(RoadShieldType type, String... texts) throws Exception
  {
    var shieldConstructor = RoadShield.class.getDeclaredConstructors()[0];
    shieldConstructor.setAccessible(true);
    RoadShield[] shields = new RoadShield[texts.length];
    for (int i = 0; i < texts.length; ++i)
      shields[i] = (RoadShield) shieldConstructor.newInstance(type, texts[i], null);
    var constructor = RoadShieldInfo.class.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    return (RoadShieldInfo) constructor.newInstance(shields, 0, texts.length, 0, 0);
  }

  @Test
  public void identicalValuesFromNewNativeObjectsDoNotNotify() throws Exception
  {
    Data old = new Data();
    old.shields = shields("M4");
    Data current = new Data();
    current.shields = shields(new String("M4"));
    NavigationSnapshot previous = old.snapshot();
    NavigationSnapshot next = current.snapshot();
    for (String path : PATHS)
      assertTrue(path, next.hasSameData(path, previous));
  }

  @Test
  public void freshFixRenewsIsaAndCameraWithoutRebuildingInstructions() throws Exception
  {
    Data data = new Data();
    data.camera = new double[] {100, 15, 55, 37, 0};
    NavigationSnapshot previous = data.snapshot();
    data.fix += 1_000_000_000L;
    NavigationSnapshot next = data.snapshot();
    assertFalse(next.hasSameData("guidance", previous));
    assertFalse(next.hasSameData("speed_camera", previous));
    for (String path : new String[] {"maneuver", "lanes", "direction_signs", "routes"})
      assertTrue(path, next.hasSameData(path, previous));
  }

  @Test
  public void distanceIsPartOfLanesAndSignsContract() throws Exception
  {
    Data data = new Data();
    data.shields = shields("M4");
    NavigationSnapshot previous = data.snapshot();
    data.turnMeters = 99;
    NavigationSnapshot next = data.snapshot();
    for (String path : new String[] {"maneuver", "lanes", "direction_signs"})
      assertFalse(path, next.hasSameData(path, previous));
    assertTrue(next.hasSameData("routes", previous));
    assertTrue(next.hasSameData("guidance", previous));
    data.turnMeters = 100.1; // Still the same integer distance exposed in the cursor.
    assertTrue(data.snapshot().hasSameData("lanes", previous));
    data.turn = new Distance(328, "328", (byte) 2);
    assertFalse(data.snapshot().hasSameData("lanes", previous));
  }

  @Test
  public void laneDirectionHighlightOrderAndRemovalArePublished() throws Exception
  {
    Data data = new Data();
    NavigationSnapshot previous = data.snapshot();
    data.lanes = new LaneInfo[] {lane(LaneWay.Right, LaneWay.Right), lane(LaneWay.Through, LaneWay.None)};
    assertFalse(data.snapshot().hasSameData("lanes", previous));
    data.lanes = new LaneInfo[] {lane(LaneWay.Left, LaneWay.None), lane(LaneWay.Through, LaneWay.None)};
    assertFalse(data.snapshot().hasSameData("lanes", previous));
    data.lanes = new LaneInfo[] {lane(LaneWay.Through, LaneWay.None), lane(LaneWay.Left, LaneWay.Left)};
    assertFalse(data.snapshot().hasSameData("lanes", previous));
    data.lanes = null;
    NavigationSnapshot absent = data.snapshot();
    assertFalse(absent.hasSameData("lanes", previous));
    data.turnMeters = 50;
    data.lanes = new LaneInfo[0];
    assertTrue(data.snapshot().hasSameData("lanes", absent));
    assertFalse(previous.hasSameData("lanes", absent));
  }

  @Test
  public void unrelatedChangesDoNotNotifyLanes() throws Exception
  {
    Data data = new Data();
    NavigationSnapshot previous = data.snapshot();
    data.limit = 20;
    data.street = "Other";
    data.eta = 590;
    data.remainingMeters = 1400;
    data.nextStreet = "Another";
    data.direction = CarDirection.TurnRight;
    data.exit = 3;
    NavigationSnapshot next = data.snapshot();
    assertTrue(next.hasSameData("lanes", previous));
    assertFalse(next.hasSameData("guidance", previous));
    assertFalse(next.hasSameData("routes", previous));
    assertFalse(next.hasSameData("maneuver", previous));
  }

  @Test
  public void signTextOrderAndRemovalArePublished() throws Exception
  {
    Data data = new Data();
    data.shields = shields("M4", "E115");
    NavigationSnapshot previous = data.snapshot();
    data.shields = shields("M4", "E116");
    assertFalse(data.snapshot().hasSameData("direction_signs", previous));
    data.shields = shields("E115", "M4");
    assertFalse(data.snapshot().hasSameData("direction_signs", previous));
    data.shields = null;
    NavigationSnapshot absent = data.snapshot();
    assertFalse(absent.hasSameData("direction_signs", previous));
    data.turnMeters = 50;
    data.shields = shields();
    assertTrue(data.snapshot().hasSameData("direction_signs", absent));
  }

  @Test
  public void signColorChangeNotifiesEvenWhenTextIsUnchanged() throws Exception
  {
    Data data = new Data();
    data.shields = shields(RoadShieldType.GenericBlue, "M-5");
    NavigationSnapshot previous = data.snapshot();
    data.shields = shields(RoadShieldType.GenericBlue, new String("M-5"));
    assertTrue(data.snapshot().hasSameData("direction_signs", previous));
    data.shields = shields(RoadShieldType.GenericGreen, "M-5");
    assertFalse(data.snapshot().hasSameData("direction_signs", previous));
    assertTrue(data.snapshot().hasSameData("lanes", previous));
  }

  @Test
  public void expiryClearsInstructionsOnceAndFreshFixRestoresThem() throws Exception
  {
    Data data = new Data();
    data.shields = shields("M4");
    data.camera = new double[] {100, 15, 55, 37, 0};
    NavigationSnapshot live = data.snapshot();
    NavigationSnapshot published = live.validAt(FIX);
    long expiredAt = FIX + 5_001_000_000L;
    NavigationSnapshot expired = live.validAt(expiredAt);
    assertSame(NavigationSnapshot.EMPTY, expired);
    for (String path : PATHS)
    {
      assertFalse(path, expired.hasSameData(path, published));
      assertTrue(path, live.validAt(expiredAt + 1).hasSameData(path, expired));
    }
    data.fix = expiredAt;
    NavigationSnapshot restored = data.snapshot().validAt(expiredAt);
    for (String path : PATHS)
      assertFalse(path, restored.hasSameData(path, expired));
  }

  @Test
  public void routeCancellationClearsVisualDataButKeepsCruisingSpeedLimit() throws Exception
  {
    NavigationSnapshot route = new Data().snapshot();
    RoadInfo road = new RoadInfo(true, 15, "Road", new double[0]);
    NavigationSnapshot cruising = new NavigationSnapshot(null, road.camera, new double[3], road, FIX);
    for (String path : new String[] {"guidance", "maneuver", "lanes", "routes"})
      assertFalse(path, cruising.hasSameData(path, route));
    assertEquals(15, cruising.currentSpeedLimitMps(FIX), 0);
    NavigationSnapshot updatedRoad =
        new NavigationSnapshot(null, road.camera, new double[3], new RoadInfo(true, 20, "Other", road.camera), FIX);
    assertFalse(updatedRoad.hasSameData("guidance", cruising));
    assertTrue(updatedRoad.hasSameData("lanes", cruising));
  }
}
