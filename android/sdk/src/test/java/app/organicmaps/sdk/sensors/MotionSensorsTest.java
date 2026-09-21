package app.organicmaps.sdk.sensors;

import static org.junit.Assert.*;

import org.junit.Test;

public class MotionSensorsTest
{
  @Test
  public void vectorConversionRejectsInvalidValuesAndChecksMeasurementAge()
  {
    long now = 5_000_000_000L;
    var r = MotionSensors.convert(new float[] {1, 2, 3}, 2, now);
    assertEquals(2, r.x(), 0.0);
    assertEquals(6, r.z(), 0.0);
    assertTrue(r.isFresh(now));
    assertFalse(r.isFresh(now - 1));
    assertFalse(r.isFresh(now + 2_000_000_001L));
    assertNull(MotionSensors.convert(1f, 1, now));
    assertNull(MotionSensors.convert(new float[] {1, 2}, 1, now));
    assertNull(MotionSensors.convert(new Float[] {1f, null, 3f}, 1, now));
    assertNull(MotionSensors.convert(new float[] {1, Float.NaN, 3}, 1, now));
    assertNull(MotionSensors.convert(new float[] {1, 2, 3}, Double.POSITIVE_INFINITY, now));
  }

  @Test
  public void compassNeedsMagneticNorthAndGyroStopsAfterAbsoluteReferenceExpires()
  {
    MotionCompass compass = new MotionCompass();
    long t = 5_000_000_000L;
    assertTrue(Double.isNaN(compass.update("accelerometer", new MotionSensors.Reading(0, 0, 9.81, t))));
    assertTrue(Double.isNaN(compass.update("gyroscope", new MotionSensors.Reading(0, 0, 1, t))));
    assertEquals(0, compass.update("magnetometer", new MotionSensors.Reading(0, 40, 0, t)), 0.0001);
    assertEquals(-0.1, compass.update("gyroscope", new MotionSensors.Reading(0, 0, 1, t + 100_000_000L)), 0.0001);
    assertTrue(Double.isNaN(compass.update("gyroscope", new MotionSensors.Reading(0, 0, 1, t + 3_000_000_000L))));
    compass.reset();
    assertTrue(Double.isNaN(compass.update("gyroscope", new MotionSensors.Reading(0, 0, 1, t + 3_100_000_000L))));
  }

  @Test
  public void compassRejectsFreeFallAndParallelOrImplausibleMagneticFields()
  {
    MotionCompass compass = new MotionCompass();
    long t = 5_000_000_000L;
    assertTrue(Double.isNaN(compass.update("accelerometer", new MotionSensors.Reading(0, 0, 0, t))));
    assertTrue(Double.isNaN(compass.update("magnetometer", new MotionSensors.Reading(0, 40, 0, t))));
    assertEquals(0, compass.update("accelerometer", new MotionSensors.Reading(0, 0, 9.81, t)), 0.001);
    assertTrue(Double.isNaN(compass.update("magnetometer", new MotionSensors.Reading(0, 0, 40, t))));
    assertTrue(Double.isNaN(compass.update("magnetometer", new MotionSensors.Reading(0, 4000, 0, t))));
  }
}
