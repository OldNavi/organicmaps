package app.organicmaps.sdk.road;

import androidx.annotation.Keep;

/** Fixed numeric columns shared by SQLite and JNI; source identities are never reduced to numeric IDX. */
@Keep
public final class RoadEventBatch
{
  public static final int WIDTH = 9;
  public final double[] values;
  public final String[] identities;

  public RoadEventBatch(double[] values, String[] identities)
  {
    if (values.length != identities.length * WIDTH)
      throw new IllegalArgumentException("Invalid road event batch");
    this.values = values;
    this.identities = identities;
  }
}
