package app.organicmaps.road;

import static org.junit.Assert.*;

import app.organicmaps.sdk.road.RoadEventAhead;
import app.organicmaps.sdk.road.RoadEventKind;
import app.organicmaps.settings.SpeedWarningSettings;
import org.junit.Test;

public class RoadEventWarningPolicyTest
{
  private static final int ALL_KINDS = (1 << RoadEventKind.COUNT) - 1;
  private static RoadEventAhead event(int kind, double limitKmh)
  {
    return new RoadEventAhead("source", kind, 100, limitKmh / 3.6, 55, 38);
  }

  @Test
  public void bumpRequiresKnownLimitAndStrictlyMoreThanTenExtraKmh()
  {
    var bump = event(RoadEventKind.BUMP, 20);
    for (int level : new int[] {SpeedWarningSettings.IMPORTANT, SpeedWarningSettings.ALL})
    {
      assertFalse(RoadEventWarningPolicy.isEligible(bump, level, ALL_KINDS, 20 / 3.6, 0));
      assertFalse(RoadEventWarningPolicy.isEligible(bump, level, ALL_KINDS, 30 / 3.6, 0));
      assertTrue(RoadEventWarningPolicy.isEligible(bump, level, ALL_KINDS, 31 / 3.6, 0));
      assertFalse(RoadEventWarningPolicy.isEligible(event(RoadEventKind.BUMP, 0), level, ALL_KINDS, 50 / 3.6, 0));
    }
  }

  @Test
  public void levelsVisibilityAndFreshMovementAreIndependentGates()
  {
    for (int kind :
         new int[] {RoadEventKind.CAMERA, RoadEventKind.MOBILE, RoadEventKind.AVERAGE_START, RoadEventKind.AVERAGE_END,
                    RoadEventKind.RED_LIGHT, RoadEventKind.LANE_CONTROL, RoadEventKind.CHILDREN})
    {
      var event = event(kind, 60);
      assertTrue(RoadEventWarningPolicy.isEligible(event, SpeedWarningSettings.IMPORTANT, ALL_KINDS, 30, 0));
      assertFalse(RoadEventWarningPolicy.isEligible(event, SpeedWarningSettings.OFF, ALL_KINDS, 10, 0));
      assertFalse(RoadEventWarningPolicy.isEligible(event, SpeedWarningSettings.ALL, ALL_KINDS & ~(1 << kind), 10, 0));
      for (double speed : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1, 0, 0.5})
        assertFalse(RoadEventWarningPolicy.isEligible(event, SpeedWarningSettings.ALL, ALL_KINDS, speed, 0));
    }
    for (int kind : new int[] {RoadEventKind.DUMMY, RoadEventKind.VIDEO, RoadEventKind.CROSSING, RoadEventKind.POLICE})
    {
      assertFalse(RoadEventWarningPolicy.isEligible(event(kind, 60), SpeedWarningSettings.IMPORTANT, ALL_KINDS, 30, 0));
      assertTrue(RoadEventWarningPolicy.isEligible(event(kind, 60), SpeedWarningSettings.ALL, ALL_KINDS, 10, 0));
    }
  }

  @Test
  public void importantSpeedCameraUsesConfiguredOffsetWhileAllWarnsAlways()
  {
    var camera = event(RoadEventKind.CAMERA, 60);
    assertFalse(RoadEventWarningPolicy.isEligible(camera, SpeedWarningSettings.IMPORTANT, ALL_KINDS, 76 / 3.6, 16));
    assertTrue(RoadEventWarningPolicy.isEligible(camera, SpeedWarningSettings.IMPORTANT, ALL_KINDS, 77 / 3.6, 16));
    assertTrue(RoadEventWarningPolicy.isEligible(camera, SpeedWarningSettings.ALL, ALL_KINDS, 30 / 3.6, 16));
    assertTrue(RoadEventWarningPolicy.isEligible(event(RoadEventKind.CAMERA, 0), SpeedWarningSettings.IMPORTANT,
                                                 ALL_KINDS, 30 / 3.6, 16));
    assertTrue(RoadEventWarningPolicy.isEligible(event(RoadEventKind.BUMP, 20), SpeedWarningSettings.IMPORTANT,
                                                 ALL_KINDS, 31 / 3.6, 40));
  }

  @Test
  public void nativeCameraDuplicatesAreSuppressedAndASeparateApproachRearms()
  {
    var policy = new RoadEventWarningPolicy();
    var imported = event(RoadEventKind.CAMERA, 60);
    var nativeCamera = new RoadEventAhead("", RoadEventKind.CAMERA, 100, 60 / 3.6, 55.00001, 38);
    policy.announced(imported, 1000);
    assertTrue(policy.wasAnnounced(nativeCamera));
    assertFalse(policy.wasAnnounced(event(RoadEventKind.LANE_CONTROL, 0)));
    assertFalse(policy.wasAnnounced(new RoadEventAhead("another", RoadEventKind.CAMERA, 200, 60 / 3.6, 55.01, 38)));
    policy.observe(nativeCamera, 25000);
    policy.expire(40000);
    assertTrue(policy.wasAnnounced(imported));
    policy.expire(56000);
    assertFalse(policy.wasAnnounced(imported));
  }
}
