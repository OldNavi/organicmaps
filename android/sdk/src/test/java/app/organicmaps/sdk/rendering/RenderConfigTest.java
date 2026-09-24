package app.organicmaps.sdk.rendering;

import static org.junit.Assert.*;

import app.organicmaps.sdk.sensors.DeviceDetector;
import java.io.StringReader;
import org.junit.Test;
import org.kxml2.io.KXmlParser;

public class RenderConfigTest
{
  private static final String DEFAULTS = "<profile name='default'><main render_scale='1.0' max_fps='30'/>"
                                       + "<cluster render_scale='1.0' max_fps='20'/></profile>";

  private static RenderConfig parse(String profiles) throws Exception
  {
    var xml = new KXmlParser();
    xml.setInput(new StringReader("<render_config>" + profiles + "</render_config>"));
    return RenderConfig.parse(xml);
  }

  @Test
  public void keepsDefaultsForUnknownDevicesAndSeparatesScreens() throws Exception
  {
    var config =
        parse(DEFAULTS + "<profile name='rox' type='car' family='rox'>"
              + "<main render_scale='0.75' max_fps='40'/><cluster render_scale='0.5' max_fps='15'/></profile>");
    var fallback = config.select(new DeviceDetector.Device("car", "unknown", ""));
    assertEquals(new RenderConfig.Display(1.0, 30), fallback.main());
    assertEquals(new RenderConfig.Display(1.0, 20), fallback.cluster());
    var rox = config.select(new DeviceDetector.Device("car", "rox", ""));
    assertEquals(new RenderConfig.Display(0.75, 40), rox.main());
    assertEquals(new RenderConfig.Display(0.5, 15), rox.cluster());
    assertEquals(fallback, config.select(new DeviceDetector.Device("phone", "rox", "")));
  }

  @Test
  public void modelInheritsFamilyIndependentlyOfFileOrder() throws Exception
  {
    var config = parse("<profile name='model' type='car' family='rox' car_type='42'><cluster max_fps='0'/></profile>"
                       + DEFAULTS + "<profile name='family' type='car' family='rox'><main render_scale='0.75'/>"
                       + "<cluster render_scale='0.5'/></profile>");
    var profile = config.select(new DeviceDetector.Device("car", "rox", "42"));
    assertEquals("model", profile.name());
    assertEquals(new RenderConfig.Display(0.75, 30), profile.main());
    assertEquals(new RenderConfig.Display(0.5, 0), profile.cluster());
    assertEquals(20, config.select(new DeviceDetector.Device("car", "rox", "43")).cluster().maxFps());
  }

  @Test
  public void validatesUnselectedOverrides() throws Exception
  {
    for (String attributes : new String[] {"render_scale='0.49'", "render_scale='1.1'", "render_scale='NaN'",
                                           "max_fps='-1'", "max_fps='241'", "max_fpps='30'", "msaa_samples='3'"})
      assertThrows(IllegalArgumentException.class,
                   ()
                       -> parse(DEFAULTS + "<profile name='bad' type='car' family='unknown'><main " + attributes
                                + "/></profile>"));
  }

  @Test
  public void selectsAntialiasingSeparatelyForEachDisplay() throws Exception
  {
    var config = parse(DEFAULTS + "<profile name='rox' type='car' family='rox'><main msaa_samples='2'/>"
                       + "<cluster msaa_samples='4'/></profile>");
    var rox = config.select(new DeviceDetector.Device("car", "rox", ""));
    assertEquals(new RenderConfig.Display(1.0, 30, 2), rox.main());
    assertEquals(new RenderConfig.Display(1.0, 20, 4), rox.cluster());
    assertEquals(0, config.select(new DeviceDetector.Device("phone", "default", "")).main().msaaSamples());
  }

  @Test
  public void rejectsMissingDefaultsAndAmbiguousProfiles() throws Exception
  {
    assertThrows(IllegalArgumentException.class, () -> parse(""));
    assertThrows(IllegalArgumentException.class,
                 () -> parse("<profile name='default'><main render_scale='1.0' max_fps='30'/></profile>"));
    assertThrows(IllegalArgumentException.class,
                 ()
                     -> parse(DEFAULTS + "<profile name='first' type='car' family='rox'/>"
                              + "<profile name='second' type='car' family='rox'/>"));
    assertThrows(IllegalArgumentException.class,
                 () -> parse(DEFAULTS + "<profile name='model' type='car' car_type='42'/>"));
  }
}
