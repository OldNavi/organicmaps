package app.organicmaps.sdk.sensors;

import android.content.Context;
import android.content.pm.PackageManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

public final class DeviceDetector
{
  public record Device(String type, String family, String carType)
  {
  }

  private DeviceDetector() {}

  public static Device detect(Context context)
  {
    return detect(context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE),
                  DeviceDetector::readProperty);
  }

  // Same serial-property precedence as Weather: Aptiv, ROX, then GWM/Sahara.
  static Device detect(boolean automotive, Function<String, String> properties)
  {
    String family = "default";
    if (!value(properties, "persist.sys.aptiv.serial.number").isEmpty())
      family = "aptiv";
    else if (!value(properties, "persist.car.sn").isEmpty())
      family = "rox";
    else if (!value(properties, "persist.gwm.vehicle.sn").isEmpty())
      family = "sahara";
    String carType = family.equals("rox") ? value(properties, "persist.car.type") : "";
    return new Device(automotive || !family.equals("default") ? "car" : "phone", family, carType);
  }

  private static String value(Function<String, String> properties, String name)
  {
    String value = properties.apply(name);
    return value == null ? "" : value.trim();
  }

  private static String readProperty(String name)
  {
    Process process = null;
    try
    {
      process = new ProcessBuilder("/system/bin/getprop", name).start();
      try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
      {
        String value = reader.readLine();
        return value == null ? "" : value;
      }
    }
    catch (IOException e)
    {
      return "";
    }
    finally
    {
      if (process != null)
        process.destroy();
    }
  }
}
