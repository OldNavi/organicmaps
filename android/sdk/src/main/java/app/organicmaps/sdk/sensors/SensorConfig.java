package app.organicmaps.sdk.sensors;

import androidx.annotation.Nullable;
import java.lang.reflect.Array;
import java.util.Map;
import java.util.Set;

/** A logical sensor and the source-specific settings of its selected car/phone profile. */
public final class SensorConfig
{
  public enum Type
  {
    VHAL,
    ANDROID_SENSOR
  }

  static final Set<String> FIELDS =
      Set.of("enabled", "type", "property_id", "area_id", "sensor_type", "value_index", "multiplier",
             "sampling_rate_hz", "privileged", "timestamp_clock", "min_accuracy", "poll_interval_ms");
  public final String name;
  public final boolean enabled;
  public final Type type;
  public final boolean privileged;
  public final int propertyId;
  public final int areaId;
  public final int sensorType;
  public final int valueIndex;
  public final double multiplier;
  public final float samplingRateHz;
  public final int minAccuracy;
  public final boolean unixTimestamps;
  public final int pollIntervalMs;

  SensorConfig(String name, Map<String, String> values)
  {
    this.name = name;
    String enabledValue = values.getOrDefault("enabled", "false");
    if (!Set.of("true", "false").contains(enabledValue))
      throw new IllegalArgumentException("Invalid enabled flag for " + name);
    enabled = Boolean.parseBoolean(enabledValue);
    String privilegedValue = values.getOrDefault("privileged", "false");
    if (!Set.of("true", "false").contains(privilegedValue))
      throw new IllegalArgumentException("Invalid privileged flag for " + name);
    privileged = Boolean.parseBoolean(privilegedValue);
    type = switch (values.getOrDefault("type", ""))
    {
      case "vhal" -> Type.VHAL;
      case "android_sensor" -> Type.ANDROID_SENSOR;
      default -> throw new IllegalArgumentException("Unknown source type for " + name);
    };
    propertyId = Integer.decode(values.getOrDefault("property_id", "0"));
    areaId = Integer.decode(values.getOrDefault("area_id", "0"));
    sensorType = Integer.parseInt(values.getOrDefault("sensor_type", "0"));
    valueIndex = Integer.parseInt(values.getOrDefault("value_index", "0"));
    multiplier = Double.parseDouble(values.getOrDefault("multiplier", "1.0"));
    samplingRateHz = Float.parseFloat(values.getOrDefault("sampling_rate_hz", "10"));
    minAccuracy = Integer.parseInt(values.getOrDefault("min_accuracy", "1"));
    String clock = values.getOrDefault("timestamp_clock", "elapsed_realtime");
    if (!Set.of("elapsed_realtime", "unix").contains(clock) || minAccuracy < 0 || minAccuracy > 3)
      throw new IllegalArgumentException("Invalid clock or accuracy for " + name);
    unixTimestamps = clock.equals("unix");
    pollIntervalMs = Integer.parseInt(values.getOrDefault("poll_interval_ms", "0"));
    if (pollIntervalMs != 0 && (pollIntervalMs < 100 || pollIntervalMs > 60000 || type != Type.VHAL))
      throw new IllegalArgumentException("Invalid VHAL polling interval for " + name);
    if (valueIndex < 0 || !Double.isFinite(multiplier) || multiplier == 0 || !Float.isFinite(samplingRateHz)
        || samplingRateHz <= 0 || samplingRateHz > 200)
      throw new IllegalArgumentException("Invalid conversion or sampling settings for " + name);
    if (type == Type.VHAL && (propertyId <= 0 || values.containsKey("sensor_type")))
      throw new IllegalArgumentException("VHAL requires property_id and cannot use sensor_type: " + name);
    if (type == Type.ANDROID_SENSOR
        && (sensorType <= 0 || values.containsKey("property_id") || values.containsKey("area_id")))
      throw new IllegalArgumentException("Android sensor requires sensor_type, not VHAL identifiers: " + name);
  }

  public long measurementTime(long rawTimestamp, long elapsedNow, long unixNow)
  {
    if (!unixTimestamps || rawTimestamp <= 0)
      return rawTimestamp;
    long age = unixNow - rawTimestamp;
    // currentTimeMillis truncates sub-millisecond precision; larger future offsets stay invalid.
    if (age < 0 && age > -1_000_000L)
      age = 0;
    return elapsedNow - age;
  }

  /** Numeric scalars use index 0; arrays may expose several channels. The multiplier is dimensionless. */
  @Nullable
  public Double convert(Object raw)
  {
    if (raw != null && raw.getClass().isArray())
      raw = valueIndex < Array.getLength(raw) ? Array.get(raw, valueIndex) : null;
    else if (valueIndex != 0)
      return null;
    if (!(raw instanceof Number value))
      return null;
    double converted = value.doubleValue() * multiplier;
    return Double.isFinite(converted) ? converted : null;
  }
}
