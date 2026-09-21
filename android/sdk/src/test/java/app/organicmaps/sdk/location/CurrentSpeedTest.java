package app.organicmaps.sdk.location;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.location.Location;
import org.junit.Test;

public class CurrentSpeedTest
{
  private static final long NOW = 10_000_000_000L;

  private Location fix(float speed, long timestamp)
  {
    Location location = mock(Location.class);
    when(location.hasSpeed()).thenReturn(true);
    when(location.getSpeed()).thenReturn(speed);
    when(location.getElapsedRealtimeNanos()).thenReturn(timestamp);
    return location;
  }

  @Test
  public void displaySpeedPrefersMcuAndExpiresIndependentlyOfMotionSpeed()
  {
    Location gnss = fix(10, NOW);
    var mcu = new VehicleSpeedCache.Sample(12, NOW - 1_000_000_000L);
    var motion = new VehicleSpeedCache.Sample(11, NOW);
    var displayed = LocationHelper.selectDisplaySpeed(mcu, motion, gnss, NOW);
    assertEquals("speedMCU", displayed.source());
    assertEquals(12, displayed.speedMps(), 0.0);
    assertEquals(11, LocationHelper.selectCurrentSpeed(gnss, motion.speed, NOW), 0.0);
    displayed = LocationHelper.selectDisplaySpeed(mcu, motion, gnss, NOW + 1_500_000_000L);
    assertEquals("speed", displayed.source());
    assertEquals(11, displayed.speedMps(), 0.0);
    displayed = LocationHelper.selectDisplaySpeed(mcu, motion, gnss, NOW + 3_000_000_000L);
    assertEquals("gnss", displayed.source());
    assertEquals(10, displayed.speedMps(), 0.0);
    assertNull(LocationHelper.selectDisplaySpeed(mcu, motion, gnss, NOW + 6_000_000_000L));
  }

  @Test
  public void vehicleSpeedTakesPriorityIncludingTrustedZeroAndReverse()
  {
    Location location = fix(20, NOW);
    assertEquals(0.0, LocationHelper.selectCurrentSpeed(location, 0.0, NOW), 0.0);
    assertEquals(5.0, LocationHelper.selectCurrentSpeed(location, -5.0, NOW), 0.0);
    assertEquals(8.0, LocationHelper.selectCurrentSpeed(null, 8.0, NOW), 0.0);
  }

  @Test
  public void unavailableVehicleSpeedFallsBackToOriginalGnssMeasurement()
  {
    Location location = fix(20, NOW - 3_000_000_000L);
    assertEquals(20.0, LocationHelper.selectCurrentSpeed(location, null, NOW), 0.0);
    assertEquals(20.0, LocationHelper.selectCurrentSpeed(location, Double.NaN, NOW), 0.0);
    assertNull(LocationHelper.selectCurrentSpeed(location, null, NOW + 3_000_000_000L));
  }

  @Test
  public void missingStaleFutureAndInvalidGnssSpeedAreUnknownNotStopped()
  {
    assertNull(LocationHelper.selectCurrentSpeed(null, null, NOW));
    assertNull(LocationHelper.selectCurrentSpeed(fix(20, NOW - 5_000_000_001L), null, NOW));
    assertNull(LocationHelper.selectCurrentSpeed(fix(20, NOW + 1), null, NOW));
    assertNull(LocationHelper.selectCurrentSpeed(fix(20, 0), null, NOW));
    assertNull(LocationHelper.selectCurrentSpeed(fix(-1, NOW), null, NOW));
    assertNull(LocationHelper.selectCurrentSpeed(fix(Float.NaN, NOW), null, NOW));
    assertNull(LocationHelper.selectCurrentSpeed(fix(Float.POSITIVE_INFINITY, NOW), null, NOW));
    Location noSpeed = fix(0, NOW);
    when(noSpeed.hasSpeed()).thenReturn(false);
    assertNull(LocationHelper.selectCurrentSpeed(noSpeed, null, NOW));
    assertEquals(0.0, LocationHelper.selectCurrentSpeed(fix(0, NOW), null, NOW), 0.0);
  }
}
