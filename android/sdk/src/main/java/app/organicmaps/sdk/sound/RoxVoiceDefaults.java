package app.organicmaps.sdk.sound;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/** Uses the same persist-property identification and Aptiv precedence as Weather. */
public final class RoxVoiceDefaults
{
  private RoxVoiceDefaults() {}

  public static boolean shouldSelect(String flavor, boolean hasSavedChoice)
  {
    return shouldSelect(flavor, hasSavedChoice, RoxVoiceDefaults::readProperty);
  }

  static boolean shouldSelect(String flavor, boolean hasSavedChoice, Function<String, String> properties)
  {
    if (!"auto".equals(flavor) || hasSavedChoice)
      return false;
    String aptiv = properties.apply("persist.sys.aptiv.serial.number");
    if (aptiv != null && !aptiv.trim().isEmpty())
      return false;
    String rox = properties.apply("persist.car.sn");
    return rox != null && !rox.trim().isEmpty();
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
