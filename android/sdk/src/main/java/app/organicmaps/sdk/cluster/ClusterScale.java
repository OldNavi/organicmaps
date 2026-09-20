package app.organicmaps.sdk.cluster;

/** Multiplier for a display's advertised DPI, independent of camera zoom. */
public final class ClusterScale
{
  public static final double DEFAULT = 1.0;

  private ClusterScale() {}

  public static double parse(Object value)
  {
    double scale = value == null ? DEFAULT : Double.parseDouble(value.toString().trim());
    if (!Double.isFinite(scale) || scale < 0.5 || scale > 3.0)
      throw new IllegalArgumentException("Cluster scale must be between 0.5 and 3.0");
    return scale;
  }
}
