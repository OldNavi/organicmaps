package app.organicmaps.sdk.location;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class VehicleSpeedCacheTest
{
  @Test
  public void availableZeroIsUntrustedUntilNonzeroThenStopsAreValid()
  {
    VehicleSpeedCache cache = new VehicleSpeedCache();
    long time = 10_000_000_000L;
    for (int i = 0; i < 20; ++i)
    {
      cache.update(new float[] {0.0f, 100.0f}, 1.0, time + i, time + i);
      assertNull(cache.get(time + i));
    }
    cache.update(5.0f, 1.0, time + 30, time + 30);
    assertEquals(5.0, cache.get(time + 30), 0.0);
    cache.update(0.0f, 1.0, time + 40, time + 40);
    assertEquals(0.0, cache.get(time + 40), 0.0);
    cache.clear();
    cache.update(0.0f, 1.0, time + 50, time + 50);
    assertEquals(0.0, cache.get(time + 50), 0.0);
    cache.reset();
    cache.update(0.0f, 1.0, time + 60, time + 60);
    assertNull(cache.get(time + 60));
  }

  @Test
  public void invalidNonzeroCannotArmThePropertyAndClearsPredictionInput()
  {
    List<VehicleSpeedCache.Sample> events = new ArrayList<>();
    VehicleSpeedCache cache = new VehicleSpeedCache(events::add);
    long time = 10_000_000_000L;
    cache.update(Float.NaN, 1.0, time, time);
    cache.update(76.0f, 1.0, time + 1, time + 1);
    cache.update(0.0f, 1.0, time + 2, time + 2);
    assertNull(cache.get(time + 2));
    assertTrue(events.isEmpty());
    cache.update(-5.0f, 1.0, time + 3, time + 3);
    assertEquals(-5.0, cache.get(time + 3), 0.0);
    cache.update(Float.POSITIVE_INFINITY, 1.0, time + 4, time + 4);
    assertNull(cache.get(time + 4));
    assertEquals(2, events.size());
    assertNull(events.get(1));
  }

  @Test
  public void vendorSpeedUsesItsOwnPermission()
  {
    assertEquals(VehicleSpeedSource.PERMISSION_SPEED, VehicleSpeedSource.requiredPermission(0x11600207));
    assertEquals(VehicleSpeedSource.PERMISSION_VENDOR_EXTENSION, VehicleSpeedSource.requiredPermission(0x216116E4));
  }

  @Test
  public void standardSpeedKeepsMetersPerSecondAndReverseSign()
  {
    assertEquals(12.5, VehicleSpeedCache.convert(12.5f, 1.0), 0.0001);
    assertEquals(-5.0, VehicleSpeedCache.convert(-5.0f, 1.0), 0.0001);
    assertEquals(0.0, VehicleSpeedCache.convert(0.0f, 1.0), 0.0);
  }

  @Test
  public void configurableVendorUnitsSupportScalarAndArrayValues()
  {
    assertEquals(10.0, VehicleSpeedCache.convert(36.0f, 1.0 / 3.6), 0.0001);
    assertEquals(20.0, VehicleSpeedCache.convert(new Float[] {72.0f, 99.0f}, 1.0 / 3.6), 0.0001);
    assertEquals(20.0, VehicleSpeedCache.convert(new float[] {72.0f}, 1.0 / 3.6), 0.0001);
    assertEquals(20.0, VehicleSpeedCache.convert(new int[] {72}, 1.0 / 3.6), 0.0001);
    assertEquals(20.0, VehicleSpeedCache.convert(new Integer[] {72}, 1.0 / 3.6), 0.0001);
    assertEquals(20.0, VehicleSpeedCache.convert(new long[] {72}, 1.0 / 3.6), 0.0001);
    assertEquals(20.0, VehicleSpeedCache.convert(new double[] {72.0}, 1.0 / 3.6), 0.0001);
    assertNull(VehicleSpeedCache.convert(new Float[0], 1.0));
    assertNull(VehicleSpeedCache.convert(new float[0], 1.0));
    assertNull(VehicleSpeedCache.convert(new Float[] {null}, 1.0));
    assertNull(VehicleSpeedCache.convert(new String[] {"36"}, 1.0));
    assertNull(VehicleSpeedCache.convert("36", 1.0));
    assertNull(VehicleSpeedCache.convert(Float.NaN, 1.0));
    assertNull(VehicleSpeedCache.convert(Float.POSITIVE_INFINITY, 1.0));
    assertNull(VehicleSpeedCache.convert(10.0f, -1.0));
  }

  @Test
  public void staleAndFutureSamplesCannotReplaceGnssSpeed()
  {
    VehicleSpeedCache cache = new VehicleSpeedCache();
    long timestamp = 10_000_000_000L;
    cache.update(10.0f, 1.0, timestamp, timestamp);
    assertEquals(10.0, cache.get(timestamp + 1_000_000_000L), 0.0);
    assertNull(cache.get(timestamp + VehicleSpeedCache.MAX_AGE_NS + 1));
    assertNull(cache.get(timestamp - 1));
    cache.clear();
    assertNull(cache.get(timestamp));
    cache.update(20.0f, 1.0, timestamp + 1, timestamp);
    assertNull(cache.get(timestamp));
    cache.update(20.0f, 1.0, timestamp, timestamp + VehicleSpeedCache.MAX_AGE_NS + 1);
    assertNull(cache.get(timestamp + VehicleSpeedCache.MAX_AGE_NS + 1));
  }

  @Test
  public void delayedOrRepeatedEventsDoNotRefreshTheSample()
  {
    VehicleSpeedCache cache = new VehicleSpeedCache();
    long timestamp = 10_000_000_000L;
    cache.update(10.0f, 1.0, timestamp, timestamp);
    cache.update(20.0f, 1.0, timestamp - 1, timestamp);
    cache.update(30.0f, 1.0, timestamp, timestamp + 1);
    assertEquals(10.0, cache.get(timestamp + 1), 0.0);
    cache.update(0.0f, 1.0, timestamp + 100, timestamp + 100);
    assertEquals(0.0, cache.get(timestamp + 100), 0.0);
  }
}
