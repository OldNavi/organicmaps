package app.organicmaps.sdk.cluster;

import static org.junit.Assert.*;

import org.junit.Test;

public class ClusterScaleTest
{
  @Test
  public void queryAndBundleValuesUseTheSameScale()
  {
    assertEquals(1.0, ClusterScale.parse(null), 0.0);
    assertEquals(1.5, ClusterScale.parse(" 1.5 "), 0.0);
    assertEquals(1.5, ClusterScale.parse(1.5), 0.0);
    assertEquals(1.0, ClusterScale.parse(1), 0.0);
  }

  @Test
  public void invalidScaleIsRejectedBeforeStartingTheService()
  {
    for (Object value : new Object[] {0, -1, 0.49, 3.01, "", "auto", "NaN", "Infinity"})
      assertThrows(IllegalArgumentException.class, () -> ClusterScale.parse(value));
  }
}
