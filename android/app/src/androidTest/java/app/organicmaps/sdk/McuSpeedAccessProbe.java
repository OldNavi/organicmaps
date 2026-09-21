package app.organicmaps.sdk;

import android.app.Activity;
import android.app.Instrumentation;
import android.car.Car;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Read-only diagnostic; uses the target application's UID without granting or adopting permissions. */
public class McuSpeedAccessProbe extends Instrumentation
{
  @Override
  public void onCreate(Bundle arguments)
  {
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart()
  {
    try
    {
      finish(Activity.RESULT_OK, readMcu());
    }
    catch (Exception e)
    {
      Bundle error = new Bundle();
      error.putString("probe_error", e.getClass().getSimpleName() + ": " + e.getMessage());
      finish(Activity.RESULT_CANCELED, error);
    }
  }

  private Bundle readMcu() throws Exception
  {
    var context = getTargetContext();
    var packages = context.getPackageManager();
    if (packages.getApplicationInfo(context.getPackageName(), 0).uid != Process.myUid())
      throw new IllegalStateException("Probe is not running under the target app UID");
    Bundle result = new Bundle();
    result.putInt("uid", Process.myUid());
    result.putString("package", context.getPackageName());
    result.putBoolean("platform_signed",
                      packages.checkSignatures("android", context.getPackageName()) == PackageManager.SIGNATURE_MATCH);
    result.putBoolean("vendor_permission", context.checkSelfPermission("android.car.permission.CAR_VENDOR_EXTENSION")
                                               == PackageManager.PERMISSION_GRANTED);
    HandlerThread worker = new HandlerThread("McuAccessProbe");
    worker.start();
    Handler handler = new Handler(worker.getLooper());
    CountDownLatch done = new CountDownLatch(1);
    Car[] connection = new Car[1];
    handler.post(() -> {
      connection[0] = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, (car, ready) -> {
        if (!ready)
          return;
        handler.post(() -> {
          try
          {
            CarPropertyManager manager = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
            CarPropertyValue<?> value = manager.getProperty(0x21408CAE, 0);
            result.putBoolean("read_succeeded", value != null);
            if (value != null)
            {
              result.putInt("status", value.getStatus());
              result.putLong("timestamp", value.getTimestamp());
              result.putString("value_kmh", String.valueOf(value.getValue()));
              result.putString("value_type",
                               value.getValue() == null ? "null" : value.getValue().getClass().getSimpleName());
            }
          }
          catch (RuntimeException e)
          {
            result.putBoolean("read_succeeded", false);
            result.putString("read_error", e.getClass().getSimpleName() + ": " + e.getMessage());
          }
          finally
          {
            done.countDown();
          }
        });
      });
    });
    try
    {
      if (!done.await(10, TimeUnit.SECONDS))
        throw new IllegalStateException("Car Service did not answer");
      return result;
    }
    finally
    {
      CountDownLatch closed = new CountDownLatch(1);
      handler.post(() -> {
        if (connection[0] != null)
          connection[0].disconnect();
        worker.quitSafely();
        closed.countDown();
      });
      if (!closed.await(5, TimeUnit.SECONDS))
        throw new IllegalStateException("Car connection did not close");
    }
  }
}
