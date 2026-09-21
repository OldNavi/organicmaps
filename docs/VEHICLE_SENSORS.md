# Vehicle and phone sensor profiles

Configuration: `android/sdk/src/main/res/xml/vehicle_sensors.xml`.

```xml
<sensor_config>
  <device type="car">
    <car_variant name="default">
      <sensors>
        <speed>
          <enabled>true</enabled>
          <privileged>false</privileged>
          <type>vhal</type>
          <property_id>0x11600207</property_id>
          <area_id>0</area_id>
          <value_index>0</value_index>
          <multiplier>1.0</multiplier>
          <sampling_rate_hz>10</sampling_rate_hz>
        </speed>
        <gyroscope>
          <enabled>true</enabled>
          <privileged>false</privileged>
          <type>android_sensor</type>
          <sensor_type>4</sensor_type>
          <multiplier>1.0</multiplier>
          <sampling_rate_hz>50</sampling_rate_hz>
        </gyroscope>
      </sensors>
    </car_variant>
    <car_variant name="rox" family="rox"><sensors /></car_variant>
    <car_variant name="aptiv" family="aptiv"><sensors /></car_variant>
    <car_variant name="sahara" family="sahara"><sensors /></car_variant>
  </device>
  <device type="phone">
    <car_variant name="default">
      <sensors>
        <speed>
          <enabled>false</enabled>
          <type>vhal</type>
          <property_id>0x11600207</property_id>
        </speed>
      </sensors>
    </car_variant>
  </device>
</sensor_config>
```

Each device type has its own mandatory `default`. A selected variant inherits individual fields
and entire missing sensors from that default. Defaults are never shared between car and phone.
Changing `type` drops inherited source identifiers; the override must supply the new identifier.
Unknown fields/types, duplicate sensor names, invalid numbers and conflicting identifiers fail
configuration validation instead of silently selecting a different source.

## Detection

`DeviceDetector` follows Weather's precedence: `persist.sys.aptiv.serial.number` selects Aptiv,
then `persist.car.sn` selects ROX, then `persist.gwm.vehicle.sn` selects GWM (profile family
`sahara`). A nonempty recognized serial property or `FEATURE_AUTOMOTIVE` selects `car`;
otherwise the device uses `phone`. Only presence is tested; serial numbers are not retained/logged.
Unknown automotive head units use car/default.

A profile may specify `car_type` for an exact ROX `persist.car.type` match. A matching model
profile wins over a family-wide one regardless of declaration order. Both inherit directly from
the device default. An empty or unknown model falls back to the family, then default.

## Fields

| Field | Meaning |
|---|---|
| `enabled` | Whether this source may be subscribed; default false |
| `privileged` | Require the app's signing certificate to match package `android`; default false |
| `type` | `vhal` or `android_sensor` |
| `property_id` | VHAL property ID, decimal or `0x` hexadecimal; required for VHAL |
| `area_id` | VHAL area, default global `0` |
| `sensor_type` | Android `Sensor.TYPE_*` number; required for Android sensors |
| `value_index` | Scalar consumer's numeric-array channel, default 0; scalar values require 0 |
| `multiplier` | Finite, nonzero unit/sign conversion factor, default 1; independent of DPI |
| `timestamp_clock` | `elapsed_realtime` (default) or `unix`; explicit conversion keeps measurement age |
| `min_accuracy` | Minimum Android accuracy status 0..3, default 1 |
| `poll_interval_ms` | Optional VHAL ON_CHANGE read interval; 0 disables it, 100..60000 ms enables it |
| `sampling_rate_hz` | Requested frequency (0 < rate <= 200), default 10; VHAL rate is clamped to supported limits |

`privileged` does not grant permissions. A platform-signed app still needs the property's read
permission. With the flag set, an unsigned/non-platform app does not connect to that source and
never requests CAR_SPEED interactively for it. Vendor speed checks CAR_VENDOR_EXTENSION;
standard speed checks CAR_SPEED. Disabled or inaccessible speed falls back to fresh GNSS.

## Processing

Speed preserves signed values, timestamp/age filtering, numeric scalar/array support and the
nonzero trust trigger: an initially constant zero cannot override GNSS; after the first valid
nonzero measurement, zero is accepted. Reconnection resets trust. Speed still feeds the native
position interpolator independently of GNSS fixes.

Accelerometer, gyroscope and magnetometer are enabled in both device defaults. Their source type
can also be changed to VHAL. Motion consumers take all three numeric axes, apply the multiplier,
and reject invalid, old, future and out-of-order measurements. Immutable readings are available
through `SensorHelper.getMotionSensorReading()` and `SensorListener.onMotionSensorUpdated()`.
Missing physical sensors are not substituted with made-up values. Subscriptions follow location
start/stop; worker callbacks from a previous session cannot reappear after stop/restart.

The system rotation-vector compass retains priority. When unavailable, the fallback derives
magnetic north from acceleration/gravity and magnetic field, rejecting free fall, strong linear
acceleration and implausible/parallel magnetic fields. Gyro angular velocity projected onto gravity
interpolates heading between absolute references, with bounded gaps and freshness. No compass
heading is produced from gyro/acceleration alone on a car without a magnetometer. Motion inputs
are not yet used for translational dead reckoning or as a replacement for GNSS course.

The tested ROX BMA2x2/BMG160 HAL sends Unix nanosecond timestamps and constant accuracy 0.
Only the ROX profile overrides `timestamp_clock=unix` and `min_accuracy=0` for these two sensors.
Unix time is converted to elapsed-realtime while preserving sample age, not replaced with receipt
time. Nonfinite, stale, future and reordered samples are still rejected. Other profiles retain the
standard Android elapsed clock and reject accuracy 0.

## Instrument-cluster display speed

`speedMCU` is separate from kinematic `speed`. Car/default uses the standard
`PERF_VEHICLE_SPEED_DISPLAY` property `0x11600208`; phone/default disables it. ROX overrides
it with `0x21408CAE` (557878446), Integer km/h, multiplier `1 / 3.6`, and `privileged=true`.
This property is present as ON_CHANGE on the tested head unit. It is also read once per second;
reads preserve the VHAL measurement timestamp and never make stale data look fresh.

The current-speed circles, navigation speed readout and provider select fresh data in this order:
`speedMCU`, `speed`, GNSS. Unknown speed is a dash in the UI and null with `speed_valid=0` in the
provider. Neither MCU speed nor its expiry is sent to the native position interpolator. The
kinematic speed source and GNSS retain that responsibility. Both speed sources keep independent
nonzero trust gates and expire after two seconds; GNSS speed expires after five seconds.

`content://organicmaps.auto.navi/speed` returns `speed` (m/s), `speed_valid`, `speed_source`
(`speedMCU`, `speed`, `gnss`, `none`), `speed_unit` (`m/s`) and `speed_age_ms` (-1 if unknown).
The same columns are appended to `/guidance` without changing existing columns. Speed freshness
is independent of the road/GNSS snapshot, so a fresh MCU speed can still be returned without a
position. Notifications are coalesced to at most four per second, including expiry/fallback,
and work during the existing background location session as well as with the map Activity open.
