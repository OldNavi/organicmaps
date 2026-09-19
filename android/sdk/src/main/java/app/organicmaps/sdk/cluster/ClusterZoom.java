package app.organicmaps.sdk.cluster;

public final class ClusterZoom
{
  public static final int AUTO = 0;
  public static final int DEFAULT = AUTO;
  private ClusterZoom() {}

  public static boolean isValid(int zoom)
  {
    return zoom == AUTO || (zoom >= 1 && zoom <= 20);
  }

  public static int parse(Object value)
  {
    if (value == null)
      return DEFAULT;
    String text = value.toString().trim();
    if (text.isEmpty() || "auto".equalsIgnoreCase(text))
      return AUTO;
    int zoom = Integer.parseInt(text);
    if (!isValid(zoom))
      throw new IllegalArgumentException("Cluster zoom must be auto, 0, or an integer between 1 and 20");
    return zoom;
  }
}
