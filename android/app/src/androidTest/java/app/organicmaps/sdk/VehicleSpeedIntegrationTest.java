package app.organicmaps.sdk;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.pm.PackageManager;
import android.os.SystemClock;
import androidx.core.content.ContextCompat;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.sdk.location.VehicleSpeedSource;
import app.organicmaps.sdk.sensors.SensorConfig;
import app.organicmaps.sdk.sensors.VehicleSensorConfig;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class VehicleSpeedIntegrationTest
{
  @Test
  public void sensorProfilesAreLoadedFromPackagedXml()
  {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var profile = VehicleSensorConfig.get(context);
    SensorConfig speed = profile.sensor("speed");
    assertTrue(Double.isFinite(speed.multiplier) && speed.multiplier > 0);
    assertTrue(profile.sensors.containsKey("accelerometer"));
    assertTrue(profile.sensors.containsKey("gyroscope"));
    assertTrue(profile.sensors.containsKey("magnetometer"));
    if (profile.name.equals("rox"))
    {
      assertEquals(0x2160152B, speed.propertyId);
      assertEquals(1.0, speed.multiplier, 0.0);
      assertTrue(speed.privileged);
      assertEquals(SensorConfig.Type.VHAL, speed.type);
      assertEquals(12.5, speed.convert(12.5f), 0.0001);
      assertEquals(0x21408CAE, profile.sensor("speedMCU").propertyId);
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void readsVehicleSpeedAndClearsItAfterDisconnect() throws Exception
  {
    // Requires an enabled property and its read permission; reads only.
    assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("carSensors")));
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var context = instrumentation.getTargetContext();
    assumeTrue(VehicleSpeedSource.isEnabled(context));
    int propertyId = VehicleSensorConfig.get(context).sensor("speed").propertyId;
    String permission = (propertyId & 0xf0000000) == 0x20000000 ? VehicleSpeedSource.PERMISSION_VENDOR_EXTENSION
                                                                : VehicleSpeedSource.PERMISSION_SPEED;
    assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(context, permission));
    VehicleSpeedSource source = new VehicleSpeedSource(context);
    AtomicReference<Double> speed = new AtomicReference<>();
    try
    {
      instrumentation.runOnMainSync(source::start);
      long deadline = SystemClock.elapsedRealtime() + 10000;
      do
      {
        instrumentation.runOnMainSync(() -> speed.set(source.getSpeedMetersPerSecond()));
        if (speed.get() != null)
          break;
        Thread.sleep(100);
      }
      while (SystemClock.elapsedRealtime() < deadline);
      assertNotNull("No trusted VHAL speed received (a valid nonzero measurement must arm the property)", speed.get());
      assertTrue(Double.isFinite(speed.get()));
    }
    finally
    {
      instrumentation.runOnMainSync(() -> {
        source.stop();
        assertNull(source.getSpeedMetersPerSecond());
      });
    }
  }
}
