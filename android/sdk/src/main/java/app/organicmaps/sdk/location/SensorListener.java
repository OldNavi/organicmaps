package app.organicmaps.sdk.location;

import app.organicmaps.sdk.sensors.MotionSensors;

public interface SensorListener
{
  void onCompassUpdated(double north);

  default void onMotionSensorUpdated(String name, MotionSensors.Reading reading)
  {
    // Optional raw motion input; timestamps and freshness are preserved.
  }

  default void onCompassCalibrationRecommended()
  {
    // No op.
  }

  default void onCompassCalibrationRequired()
  {
    // No op.
  }
}
