package app.organicmaps.sdk.sensors;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.Nullable;

/** Read-only sensor subscription. Callbacks run on the source worker, in measurement order. */
public interface SensorSource extends AutoCloseable
{
  interface Listener
  {
    void onValue(Object value, long timestampNanos);
    void onUnavailable();
    void onReset();
  }

  static boolean isPermitted(Context context, SensorConfig config)
  {
    return config.enabled
 && (!config.privileged
     || context.getPackageManager().checkSignatures("android", context.getPackageName())
            == PackageManager.SIGNATURE_MATCH);
  }

  @Nullable
  static SensorSource open(Context context, SensorConfig config, Listener listener)
  {
    if (!isPermitted(context, config))
      return null;
    return switch (config.type)
    {
      case VHAL ->
        Build.VERSION.SDK_INT >= 29 && "car".equals(VehicleSensorConfig.get(context).deviceType)
            ? new VhalSensorSource(context, config, listener)
            : null;
      case ANDROID_SENSOR -> AndroidSensorSource.open(context, config, listener);
    };
  }

  @Override
  void close();
}
