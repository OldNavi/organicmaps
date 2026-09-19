package app.organicmaps.intent;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Automotive deep links corresponding to the search/route operations used by RoxAssistant. */
public final class NavigationCommand
{
  public static final class Point
  {
    public final double latitude;
    public final double longitude;
    Point(String latitude, String longitude)
    {
      this.latitude = Double.parseDouble(latitude);
      this.longitude = Double.parseDouble(longitude);
      if (!Double.isFinite(this.latitude) || !Double.isFinite(this.longitude) || Math.abs(this.latitude) > 90
          || Math.abs(this.longitude) > 180)
        throw new IllegalArgumentException("Invalid navigation coordinates");
    }
  }

  public final boolean search;
  public final String text;
  public final String locale;
  public final Point center;
  public final Point origin;
  public final Point destination;
  public final String place;
  public final String title;
  public final boolean startGuidance;
  public final boolean alongRoute;

  private NavigationCommand(boolean isSearch, Map<String, String> values)
  {
    search = isSearch;
    text = values.getOrDefault("text", "");
    locale = values.get("locale");
    center = point(values, "lat", "lon");
    origin = point(values, "lat_from", "lon_from");
    destination = point(values, "lat_to", "lon_to");
    place = values.get("place_to");
    title = values.getOrDefault("name_to", "");
    startGuidance = flag(values, "start_guidance");
    alongRoute = flag(values, "along_route");
    if (search && text.isBlank())
      throw new IllegalArgumentException("Search text is empty");
    if (!search && destination == null && (place == null || place.isBlank()))
      throw new IllegalArgumentException("Route destination is missing");
    if (!search && destination != null && place != null)
      throw new IllegalArgumentException("Specify coordinates or a saved place, not both");
  }

  private static boolean flag(Map<String, String> values, String key)
  {
    String value = values.get(key);
    if (value == null || "0".equals(value) || "false".equalsIgnoreCase(value))
      return false;
    if ("1".equals(value) || "true".equalsIgnoreCase(value))
      return true;
    throw new IllegalArgumentException("Invalid " + key);
  }

  private static Point point(Map<String, String> values, String lat, String lon)
  {
    if (!values.containsKey(lat) && !values.containsKey(lon))
      return null;
    if (!values.containsKey(lat) || !values.containsKey(lon))
      throw new IllegalArgumentException("Both latitude and longitude are required");
    return new Point(values.get(lat), values.get(lon));
  }

  private static String decode(String value)
  {
    try
    {
      return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
    }
    catch (UnsupportedEncodingException e)
    {
      throw new AssertionError(e);
    }
  }

  public static NavigationCommand parse(String url)
  {
    URI uri = URI.create(url.replace(" ", "%20"));
    if (!"om".equalsIgnoreCase(uri.getScheme()))
      return null;
    String operation = uri.getRawAuthority();
    if (!"map_search".equals(operation) && !"build_route_on_map".equals(operation))
      return null;
    Map<String, String> values = new HashMap<>();
    if (uri.getRawQuery() != null)
      for (String item : uri.getRawQuery().split("&"))
      {
        String[] parts = item.split("=", 2);
        String key = decode(parts[0]);
        String value = parts.length > 1 ? decode(parts[1]) : "";
        if (values.putIfAbsent(key, value) != null)
          throw new IllegalArgumentException("Duplicate navigation parameter: " + key);
      }
    return new NavigationCommand("map_search".equals(operation), values);
  }
}
