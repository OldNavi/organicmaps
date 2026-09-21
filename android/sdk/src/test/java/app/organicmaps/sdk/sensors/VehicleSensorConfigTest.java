package app.organicmaps.sdk.sensors;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.pm.PackageManager;
import java.io.StringReader;
import java.util.Map;
import org.junit.Test;
import org.kxml2.io.KXmlParser;

public class VehicleSensorConfigTest
{
  private VehicleSensorConfig parse(String car, String profiles) throws Exception
  {
    String xml = "<sensor_config><device type='car'><car_variant name='default'><sensors>" + car
               + "</sensors></car_variant>" + profiles + "</device><device type='phone'><car_variant name='default'>"
               + "<sensors><speed><enabled>false</enabled><type>vhal</type><property_id>0x11600207</property_id>"
               + "</speed></sensors></car_variant></device></sensor_config>";
    KXmlParser parser = new KXmlParser();
    parser.setInput(new StringReader(xml));
    return VehicleSensorConfig.parse(parser);
  }

  private static final String SPEED =
      "<speed><enabled>true</enabled><type>vhal</type>"
      + "<property_id>0x11600207</property_id><multiplier>0.27777778</multiplier></speed>";

  @Test
  public void deviceDefaultsAndPartialOverridesStayIndependent() throws Exception
  {
    var config = parse(SPEED, "<car_variant name='rox' family='rox'><sensors><speed><area_id>2</area_id>"
                                  + "<value_index>1</value_index></speed></sensors></car_variant>");
    var rox = config.select(new DeviceDetector.Device("car", "rox", ""));
    assertEquals("rox", rox.name);
    assertEquals(0x11600207, rox.sensor("speed").propertyId);
    assertEquals(2, rox.sensor("speed").areaId);
    assertEquals(20.0, rox.sensor("speed").convert(new Float[] {10f, 72f}), 0.0001);
    assertNull(rox.sensor("speed").convert(72f));
    var unknown = config.select(new DeviceDetector.Device("car", "default", ""));
    assertEquals(0, unknown.sensor("speed").areaId);
    assertTrue(unknown.sensor("speed").enabled);
    assertFalse(config.select(new DeviceDetector.Device("phone", "default", "")).sensor("speed").enabled);
  }

  @Test
  public void modelSpecificProfileWinsRegardlessOfOrder() throws Exception
  {
    var config = parse(SPEED, "<car_variant name='rox-model' family='rox' car_type='42'><sensors>"
                                  + "<speed><enabled>false</enabled></speed></sensors></car_variant>"
                                  + "<car_variant name='rox' family='rox'><sensors/></car_variant>");
    assertEquals("rox-model", config.select(new DeviceDetector.Device("car", "rox", "42")).name);
    assertEquals("rox", config.select(new DeviceDetector.Device("car", "rox", "other")).name);
  }

  @Test
  public void sourceSwitchDropsVhalIdentifiers() throws Exception
  {
    var config =
        parse(SPEED, "<car_variant name='rox' family='rox'><sensors><speed><type>android_sensor</type>"
                         + "<sensor_type>123</sensor_type><multiplier>1</multiplier></speed></sensors></car_variant>");
    var speed = config.select(new DeviceDetector.Device("car", "rox", "")).sensor("speed");
    assertEquals(SensorConfig.Type.ANDROID_SENSOR, speed.type);
    assertEquals(0, speed.propertyId);
    assertEquals(123, speed.sensorType);
    assertEquals(7.0, speed.convert(7), 0.0);
  }

  @Test
  public void rejectsInvalidAndAmbiguousConfiguration() throws Exception
  {
    assertThrows(IllegalArgumentException.class, () -> parse(SPEED.replace("vhal", "unknown"), ""));
    assertThrows(IllegalArgumentException.class, () -> parse(SPEED.replace("true", "yes"), ""));
    assertThrows(IllegalArgumentException.class, () -> parse(SPEED.replace("0.27777778", "NaN"), ""));
    assertThrows(IllegalArgumentException.class,
                 () -> parse(SPEED.replace("</speed>", "<sensor_type>1</sensor_type></speed>"), ""));
    assertThrows(IllegalArgumentException.class, () -> parse(SPEED + SPEED, ""));
    var config = parse(SPEED, "<car_variant name='a' family='rox'><sensors/></car_variant>"
                                  + "<car_variant name='b' family='rox'><sensors/></car_variant>");
    assertThrows(IllegalArgumentException.class, () -> config.select(new DeviceDetector.Device("car", "rox", "")));
  }

  @Test
  public void privilegedSourceRequiresPlatformSignatureAndNeverBypassesDisabledFlag()
  {
    Context context = mock(Context.class);
    PackageManager packages = mock(PackageManager.class);
    when(context.getPackageManager()).thenReturn(packages);
    when(context.getPackageName()).thenReturn("app.organicmaps.auto");
    var config = new SensorConfig(
        "speed", Map.of("type", "vhal", "enabled", "true", "property_id", "0x216116e4", "privileged", "true"));
    when(packages.checkSignatures("android", "app.organicmaps.auto")).thenReturn(PackageManager.SIGNATURE_NO_MATCH);
    assertFalse(SensorSource.isPermitted(context, config));
    when(packages.checkSignatures("android", "app.organicmaps.auto")).thenReturn(PackageManager.SIGNATURE_MATCH);
    assertTrue(SensorSource.isPermitted(context, config));
    assertFalse(SensorSource.isPermitted(
        context, new SensorConfig("speed", Map.of("type", "vhal", "enabled", "false", "property_id", "1"))));
  }

  @Test
  public void clusterMcuIntegerKilometersPerHourUsesSeparatePropertyAndUnits()
  {
    var config = new SensorConfig("speedMCU", Map.of("enabled", "true", "type", "vhal", "property_id", "0x21408CAE",
                                                     "multiplier", "0.2777777777777778", "privileged", "true"));
    assertEquals(557878446, config.propertyId);
    assertEquals(20, config.convert(72), 0.0001);
    assertEquals(20, config.convert(new Integer[] {72}), 0.0001);
    assertTrue(config.privileged);
  }

  @Test
  public void roxUnixClockConversionPreservesAgeAndFutureSamplesStayFuture()
  {
    var config =
        new SensorConfig("gyro", Map.of("type", "android_sensor", "sensor_type", "4", "timestamp_clock", "unix"));
    long elapsed = 5_000_000_000L;
    long unix = 1_700_000_000_000_000_000L;
    assertEquals(elapsed - 50_000_000L, config.measurementTime(unix - 50_000_000L, elapsed, unix));
    assertEquals(elapsed - 3_000_000_000L, config.measurementTime(unix - 3_000_000_000L, elapsed, unix));
    assertEquals(elapsed + 50_000_000L, config.measurementTime(unix + 50_000_000L, elapsed, unix));
    assertEquals(elapsed, config.measurementTime(unix + 100_000L, elapsed, unix));
    assertEquals(0, config.measurementTime(0, elapsed, unix));
  }

  @Test
  public void detectorMatchesWeatherPrecedenceWithoutKeepingSerialNumbers()
  {
    assertEquals("phone", DeviceDetector.detect(false, key -> null).type());
    assertEquals("car", DeviceDetector.detect(true, key -> null).type());
    assertEquals("default", DeviceDetector.detect(true, key -> " ").family());
    var values = Map.of("persist.car.sn", "ROX", "persist.gwm.vehicle.sn", "GWM", "persist.car.type", "42");
    var rox = DeviceDetector.detect(false, values::get);
    assertEquals(new DeviceDetector.Device("car", "rox", "42"), rox);
    assertEquals("aptiv", DeviceDetector.detect(false, key -> "all-present").family());
    assertEquals("sahara", DeviceDetector.detect(false, Map.of("persist.gwm.vehicle.sn", "GWM")::get).family());
  }
}
