package app.organicmaps.sdk.cluster;

/** Opt-in flags for cluster presentations. */
public final class ClusterFlag
{
  private ClusterFlag() {}

  public static boolean parse(String name, Object value)
  {
    if (value == null)
      return false;
    if (value instanceof Boolean)
      return (Boolean) value;
    String text = value.toString().trim();
    if ("0".equals(text))
      return false;
    if ("1".equals(text))
      return true;
    throw new IllegalArgumentException("Cluster " + name + " must be 0 or 1");
  }
}
