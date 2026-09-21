package app.organicmaps.sdk;

import static org.junit.Assert.*;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.sdk.sensors.MotionSensors;
import app.organicmaps.sdk.sensors.SensorConfig;
import app.organicmaps.sdk.sensors.VehicleSensorConfig;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class MotionSensorIntegrationTest
{
  @Test
  public void configuredMotionSensorsDeliverFreshMeasurements() throws Exception
  {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    Context context = instrumentation.getTargetContext();
    SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    var profile = VehicleSensorConfig.get(context);
    Set<String> expected = ConcurrentHashMap.newKeySet();
    for (String name : new String[] {"accelerometer", "gyroscope", "magnetometer"})
    {
      var config = profile.sensor(name);
      if (config.enabled && config.type == SensorConfig.Type.ANDROID_SENSOR
          && manager.getDefaultSensor(config.sensorType) != null)
        expected.add(name);
    }
    CountDownLatch ready = new CountDownLatch(expected.size());
    Set<String> received = ConcurrentHashMap.newKeySet();
    MotionSensors sensors = new MotionSensors(context, new MotionSensors.Listener() {
      @Override
      public void onReading(String name, MotionSensors.Reading reading)
      {
        assertTrue(reading.isFresh(SystemClock.elapsedRealtimeNanos()));
        if (expected.contains(name) && received.add(name))
          ready.countDown();
      }
      @Override
      public void onUnavailable(String name)
      {}
    });
    // Record the HAL clock independently from the application's freshness filter.
    SensorEventListener diagnostic = new SensorEventListener() {
      private final Set<Integer> types = ConcurrentHashMap.newKeySet();
      @Override
      public void onSensorChanged(SensorEvent event)
      {
        if (types.add(event.sensor.getType()))
        {
          Bundle status = new Bundle();
          status.putString("sensor", event.sensor.getName());
          status.putLong("sensor_timestamp", event.timestamp);
          status.putLong("elapsed_timestamp", SystemClock.elapsedRealtimeNanos());
          status.putInt("accuracy", event.accuracy);
          instrumentation.sendStatus(2, status);
        }
      }
      @Override
      public void onAccuracyChanged(Sensor sensor, int accuracy)
      {}
    };
    try
    {
      instrumentation.runOnMainSync(() -> {
        for (String name : expected)
          manager.registerListener(diagnostic, manager.getDefaultSensor(profile.sensor(name).sensorType),
                                   SensorManager.SENSOR_DELAY_NORMAL);
        sensors.start();
      });
      assertTrue("Missing fresh measurements: " + expected + "; received: " + received,
                 ready.await(5, TimeUnit.SECONDS));
      instrumentation.runOnMainSync(() -> {
        for (String name : expected)
          assertNotNull(sensors.get(name));
      });
    }
    finally
    {
      instrumentation.runOnMainSync(() -> {
        sensors.close();
        manager.unregisterListener(diagnostic);
        for (String name : expected)
          assertNull(sensors.get(name));
      });
    }
  }
}
