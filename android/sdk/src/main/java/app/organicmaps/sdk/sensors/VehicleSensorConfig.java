package app.organicmaps.sdk.sensors;

import android.content.Context;
import android.content.res.XmlResourceParser;
import app.organicmaps.sdk.R;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/** Packaged XML is validated once; profile overrides never cross the car/phone boundary. */
public final class VehicleSensorConfig
{
  public static final class Profile
  {
    public final String deviceType;
    public final String name;
    public final Map<String, SensorConfig> sensors;

    private Profile(String deviceType, String name, Map<String, SensorConfig> sensors)
    {
      this.deviceType = deviceType;
      this.name = name;
      this.sensors = Map.copyOf(sensors);
    }

    public SensorConfig sensor(String name)
    {
      SensorConfig sensor = sensors.get(name);
      if (sensor == null)
        throw new IllegalArgumentException("No sensor " + name + " in profile " + this.name);
      return sensor;
    }
  }

  private static final class Variant
  {
    String name;
    String family;
    String carType;
    final Map<String, Map<String, String>> sensors = new LinkedHashMap<>();
  }

  private static Profile sProfile;
  private final Map<String, List<Variant>> mDevices = new LinkedHashMap<>();

  public static synchronized Profile get(Context context)
  {
    if (sProfile == null)
    {
      try (XmlResourceParser xml = context.getResources().getXml(R.xml.vehicle_sensors))
      {
        sProfile = parse(xml).select(DeviceDetector.detect(context));
      }
      catch (IOException | XmlPullParserException e)
      {
        throw new IllegalStateException("Invalid vehicle_sensors.xml", e);
      }
    }
    return sProfile;
  }

  static VehicleSensorConfig parse(XmlPullParser xml) throws IOException, XmlPullParserException
  {
    VehicleSensorConfig config = new VehicleSensorConfig();
    while (xml.getEventType() != XmlPullParser.START_TAG && xml.getEventType() != XmlPullParser.END_DOCUMENT)
      xml.next();
    xml.require(XmlPullParser.START_TAG, null, "sensor_config");
    while (xml.nextTag() == XmlPullParser.START_TAG)
    {
      xml.require(XmlPullParser.START_TAG, null, "device");
      String type = requiredAttribute(xml, "type");
      if (!Set.of("car", "phone").contains(type) || config.mDevices.containsKey(type))
        throw new IllegalArgumentException("Invalid or duplicate device type: " + type);
      List<Variant> variants = new ArrayList<>();
      while (xml.nextTag() == XmlPullParser.START_TAG)
      {
        xml.require(XmlPullParser.START_TAG, null, "car_variant");
        Variant variant = new Variant();
        variant.name = requiredAttribute(xml, "name");
        variant.family = xml.getAttributeValue(null, "family");
        variant.carType = xml.getAttributeValue(null, "car_type");
        if (variants.stream().anyMatch(v -> v.name.equals(variant.name)))
          throw new IllegalArgumentException("Duplicate variant: " + variant.name);
        if ("default".equals(variant.name))
        {
          if (variant.family != null || variant.carType != null)
            throw new IllegalArgumentException("Default profile cannot have match conditions");
        }
        else if (!Set.of("rox", "aptiv", "sahara").contains(variant.family == null ? "" : variant.family))
          throw new IllegalArgumentException("Variant needs a known family: " + variant.name);
        xml.nextTag();
        xml.require(XmlPullParser.START_TAG, null, "sensors");
        while (xml.nextTag() == XmlPullParser.START_TAG)
        {
          String sensor = xml.getName();
          Map<String, String> fields = new LinkedHashMap<>();
          while (xml.nextTag() == XmlPullParser.START_TAG)
          {
            String field = xml.getName();
            if (!SensorConfig.FIELDS.contains(field) || fields.containsKey(field))
              throw new IllegalArgumentException("Unknown or duplicate field: " + sensor + "." + field);
            fields.put(field, xml.nextText().trim());
          }
          xml.require(XmlPullParser.END_TAG, null, sensor);
          if (variant.sensors.put(sensor, fields) != null)
            throw new IllegalArgumentException("Duplicate sensor: " + sensor);
        }
        xml.require(XmlPullParser.END_TAG, null, "sensors");
        xml.nextTag();
        xml.require(XmlPullParser.END_TAG, null, "car_variant");
        variants.add(variant);
      }
      xml.require(XmlPullParser.END_TAG, null, "device");
      Variant defaults = defaults(variants);
      for (Variant variant : variants)
        resolve(type, defaults, variant); // Validate even profiles not selected on this device.
      config.mDevices.put(type, variants);
    }
    xml.require(XmlPullParser.END_TAG, null, "sensor_config");
    if (!config.mDevices.keySet().equals(Set.of("car", "phone")))
      throw new IllegalArgumentException("Both car and phone defaults are required");
    return config;
  }

  Profile select(DeviceDetector.Device device)
  {
    List<Variant> variants = mDevices.get(device.type());
    Variant defaults = defaults(variants);
    Variant selected = defaults;
    for (Variant variant : variants)
    {
      if (!device.family().equals(variant.family)
          || (variant.carType != null && !variant.carType.equals(device.carType())))
        continue;
      // A model-specific profile wins over its family's generic profile, regardless of XML order.
      if (selected != defaults && (selected.carType == null) == (variant.carType == null))
        throw new IllegalArgumentException("Ambiguous sensor profiles: " + selected.name + ", " + variant.name);
      if (selected == defaults || variant.carType != null)
        selected = variant;
    }
    return resolve(device.type(), defaults, selected);
  }

  private static Profile resolve(String type, Variant defaults, Variant selected)
  {
    Map<String, Map<String, String>> values = new LinkedHashMap<>();
    defaults.sensors.forEach((name, fields) -> values.put(name, new LinkedHashMap<>(fields)));
    if (selected != defaults)
    {
      selected.sensors.forEach((name, overrides) -> {
        Map<String, String> fields = values.computeIfAbsent(name, ignored -> new LinkedHashMap<>());
        // A source change must supply its own identifiers; shared conversion settings still inherit.
        if (overrides.containsKey("type") && !overrides.get("type").equals(fields.get("type")))
        {
          fields.remove("property_id");
          fields.remove("area_id");
          fields.remove("sensor_type");
          fields.remove("min_accuracy");
          fields.remove("poll_interval_ms");
        }
        fields.putAll(overrides);
      });
    }
    Map<String, SensorConfig> sensors = new LinkedHashMap<>();
    values.forEach((name, fields) -> sensors.put(name, new SensorConfig(name, fields)));
    if (!sensors.containsKey("speed"))
      throw new IllegalArgumentException("Profile requires speed settings: " + selected.name);
    return new Profile(type, selected.name, sensors);
  }

  private static Variant defaults(List<Variant> variants)
  {
    return variants.stream()
        .filter(v -> "default".equals(v.name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Missing default sensor profile"));
  }

  private static String requiredAttribute(XmlPullParser xml, String name)
  {
    String value = xml.getAttributeValue(null, name);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Missing " + name + " on " + xml.getName());
    return value;
  }
}
