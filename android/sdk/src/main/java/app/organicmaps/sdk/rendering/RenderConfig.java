package app.organicmaps.sdk.rendering;

import android.content.Context;
import android.content.res.XmlResourceParser;
import app.organicmaps.sdk.R;
import app.organicmaps.sdk.sensors.DeviceDetector;
import app.organicmaps.sdk.util.Config;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/** Packaged per-device rendering settings; each surface keeps its own resolution and frame budget. */
public final class RenderConfig
{
  public record Display(double renderScale, int maxFps, int msaaSamples)
  {
    public Display(double renderScale, int maxFps)
    {
      this(renderScale, maxFps, 0);
    }

    public Display
    {
      if (!Double.isFinite(renderScale) || renderScale < 0.5 || renderScale > 1.0 || maxFps < 0 || maxFps > 240
          || (msaaSamples != 0 && msaaSamples != 2 && msaaSamples != 4))
        throw new IllegalArgumentException("Invalid rendering scale or FPS");
    }
  }

  public record Profile(String name, Display main, Display cluster)
  {
  }
  private record DisplayOverride(Double scale, Integer fps, Integer samples)
  {
    Display apply(Display base)
    {
      return new Display(scale == null ? base.renderScale() : scale, fps == null ? base.maxFps() : fps,
                         samples == null ? base.msaaSamples() : samples);
    }
  }
  private record Selector(String type, String family, String carType)
  {
    boolean matches(DeviceDetector.Device device)
    {
      return (type == null || type.equals(device.type())) && (family == null || family.equals(device.family()))
   && (carType == null || carType.equals(device.carType()));
    }
    int specificity()
    {
      return (type == null ? 0 : 1) + (family == null ? 0 : 2) + (carType == null ? 0 : 4);
    }
  }
  private record Variant(String name, Selector selector, DisplayOverride main, DisplayOverride cluster)
  {
  }

  private static final Profile STANDARD = new Profile("standard", new Display(1.0, 0), new Display(1.0, 20));
  private static Profile sProfile;
  private final List<Variant> mVariants = new ArrayList<>();

  public static synchronized Profile get(Context context)
  {
    if (!Config.isAuto())
      return STANDARD;
    if (sProfile == null)
    {
      try (XmlResourceParser xml = context.getResources().getXml(R.xml.vehicle_rendering))
      {
        sProfile = parse(xml).select(DeviceDetector.detect(context));
      }
      catch (IOException | XmlPullParserException e)
      {
        throw new IllegalStateException("Invalid vehicle_rendering.xml", e);
      }
    }
    return sProfile;
  }

  static RenderConfig parse(XmlPullParser xml) throws IOException, XmlPullParserException
  {
    RenderConfig config = new RenderConfig();
    while (xml.getEventType() != XmlPullParser.START_TAG && xml.getEventType() != XmlPullParser.END_DOCUMENT)
      xml.next();
    xml.require(XmlPullParser.START_TAG, null, "render_config");
    var names = new HashSet<String>();
    var selectors = new HashSet<Selector>();
    boolean hasDefaults = false;
    while (xml.nextTag() == XmlPullParser.START_TAG)
    {
      xml.require(XmlPullParser.START_TAG, null, "profile");
      validateAttributes(xml, Set.of("name", "type", "family", "car_type"));
      String name = attribute(xml, "name");
      String type = attribute(xml, "type");
      String family = attribute(xml, "family");
      String carType = attribute(xml, "car_type");
      boolean defaults = "default".equals(name);
      var selector = new Selector(type, family, carType);
      if (name == null || !names.add(name) || !selectors.add(selector) || (defaults && selector.specificity() != 0)
          || (!defaults && !"car".equals(type) && !"phone".equals(type)) || (carType != null && family == null))
        throw new IllegalArgumentException("Invalid or duplicate rendering profile: " + name);
      DisplayOverride main = null, cluster = null;
      while (xml.nextTag() == XmlPullParser.START_TAG)
      {
        validateAttributes(xml, Set.of("render_scale", "max_fps", "msaa_samples"));
        String display = xml.getName();
        String scaleText = attribute(xml, "render_scale");
        String fpsText = attribute(xml, "max_fps");
        String samplesText = attribute(xml, "msaa_samples");
        var override = new DisplayOverride(scaleText == null ? null : Double.valueOf(scaleText),
                                           fpsText == null ? null : Integer.valueOf(fpsText),
                                           samplesText == null ? null : Integer.valueOf(samplesText));
        override.apply(new Display(1.0, 0)); // Validate every profile, even when it is not selected.
        if (defaults && (override.scale() == null || override.fps() == null))
          throw new IllegalArgumentException("Default display requires scale and FPS");
        if (display.equals("main") && main == null)
          main = override;
        else if (display.equals("cluster") && cluster == null)
          cluster = override;
        else
          throw new IllegalArgumentException("Unknown or duplicate display: " + display);
        xml.nextTag();
        xml.require(XmlPullParser.END_TAG, null, display);
      }
      xml.require(XmlPullParser.END_TAG, null, "profile");
      if (defaults && (main == null || cluster == null))
        throw new IllegalArgumentException("Both default displays are required");
      hasDefaults |= defaults;
      config.mVariants.add(new Variant(name, selector, main, cluster));
    }
    xml.require(XmlPullParser.END_TAG, null, "render_config");
    if (!hasDefaults)
      throw new IllegalArgumentException("Missing default rendering profile");
    config.mVariants.sort(Comparator.comparingInt(v -> v.selector().specificity()));
    return config;
  }

  Profile select(DeviceDetector.Device device)
  {
    Display main = STANDARD.main(), cluster = STANDARD.cluster();
    String name = "default";
    // Apply general, family and model settings in that order, independent of XML order.
    for (Variant variant : mVariants)
    {
      if (!variant.selector().matches(device))
        continue;
      if (variant.main() != null)
        main = variant.main().apply(main);
      if (variant.cluster() != null)
        cluster = variant.cluster().apply(cluster);
      name = variant.name();
    }
    return new Profile(name, main, cluster);
  }

  private static void validateAttributes(XmlPullParser xml, Set<String> allowed)
  {
    for (int i = 0; i < xml.getAttributeCount(); ++i)
      if (!allowed.contains(xml.getAttributeName(i)))
        throw new IllegalArgumentException("Unknown rendering attribute: " + xml.getAttributeName(i));
  }

  private static String attribute(XmlPullParser xml, String name)
  {
    String value = xml.getAttributeValue(null, name);
    if (value != null && value.isBlank())
      throw new IllegalArgumentException("Empty " + name);
    return value;
  }
}
