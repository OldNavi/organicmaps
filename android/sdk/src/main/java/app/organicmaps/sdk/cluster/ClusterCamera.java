package app.organicmaps.sdk.cluster;

/** Per-display camera options. Anchor coordinates are fractions of the visible viewport. */
public final class ClusterCamera
{
  public static final double AUTO_TILT = -1.0;
  public static final double MAX_TILT = 55.0;
  public static final ClusterCamera DEFAULT = new ClusterCamera(AUTO_TILT, 0.5, 0.75);
  public final double tilt;
  public final double anchorX;
  public final double anchorY;

  public ClusterCamera(double tilt, double anchorX, double anchorY)
  {
    if (!Double.isFinite(tilt) || (tilt != AUTO_TILT && (tilt < 0 || tilt > MAX_TILT)))
      throw new IllegalArgumentException("Cluster tilt must be auto or between 0 and 55 degrees");
    if (!Double.isFinite(anchorX) || !Double.isFinite(anchorY) || anchorX < 0 || anchorX > 1 || anchorY < 0
        || anchorY > 1)
      throw new IllegalArgumentException("Cluster anchor coordinates must be between 0 and 1");
    this.tilt = tilt;
    this.anchorX = anchorX;
    this.anchorY = anchorY;
  }

  public static ClusterCamera parse(Object tilt, Object anchor, Object anchorX, Object anchorY)
  {
    double angle = AUTO_TILT;
    if (tilt != null && !"auto".equalsIgnoreCase(tilt.toString().trim()))
    {
      angle = Double.parseDouble(tilt.toString().trim());
      if (angle < 0)
        throw new IllegalArgumentException("Use tilt=auto for automatic perspective");
    }
    if (anchor != null)
    {
      if (anchorX != null || anchorY != null)
        throw new IllegalArgumentException("Use anchor or anchor_x/anchor_y, not both");
      String[] values = anchor.toString().split(",", -1);
      if (values.length != 2)
        throw new IllegalArgumentException("Cluster anchor must be x,y");
      anchorX = values[0];
      anchorY = values[1];
    }
    return new ClusterCamera(angle, anchorX == null ? DEFAULT.anchorX : Double.parseDouble(anchorX.toString().trim()),
                             anchorY == null ? DEFAULT.anchorY : Double.parseDouble(anchorY.toString().trim()));
  }
}
