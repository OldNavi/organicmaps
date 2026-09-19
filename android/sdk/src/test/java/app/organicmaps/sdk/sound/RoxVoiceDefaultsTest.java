package app.organicmaps.sdk.sound;

import static org.junit.Assert.*;

import java.util.Map;
import org.junit.Test;

public class RoxVoiceDefaultsTest
{
  @Test
  public void detectsRoxByPersistSerialOnlyForAuto()
  {
    Map<String, String> properties = Map.of("persist.car.sn", "test-rox");
    assertTrue(RoxVoiceDefaults.shouldSelect("auto", false, properties::get));
    assertFalse(RoxVoiceDefaults.shouldSelect("google", false, properties::get));
    assertFalse(RoxVoiceDefaults.shouldSelect("web", false, properties::get));
  }

  @Test
  public void preservesManualChoiceWithoutReadingProperties()
  {
    assertFalse(RoxVoiceDefaults.shouldSelect("auto", true, name -> { throw new AssertionError(name); }));
    assertFalse(RoxVoiceDefaults.shouldSelect("google", false, name -> { throw new AssertionError(name); }));
  }

  @Test
  public void followsWeatherAptivPrecedence()
  {
    Map<String, String> properties = Map.of("persist.car.sn", "rox", "persist.sys.aptiv.serial.number", "aptiv");
    assertFalse(RoxVoiceDefaults.shouldSelect("auto", false, properties::get));
  }

  @Test
  public void missingOrBlankPropertiesDoNotSelectRox()
  {
    assertFalse(RoxVoiceDefaults.shouldSelect("auto", false, name -> null));
    assertFalse(RoxVoiceDefaults.shouldSelect("auto", false, name -> "  "));
    Map<String, String> properties = Map.of("persist.gwm.vehicle.sn", "gwm");
    assertFalse(RoxVoiceDefaults.shouldSelect("auto", false, properties::get));
  }
}
