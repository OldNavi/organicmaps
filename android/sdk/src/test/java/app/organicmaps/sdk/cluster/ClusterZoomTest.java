package app.organicmaps.sdk.cluster;

import static org.junit.Assert.*;

import org.junit.Test;

public class ClusterZoomTest
{
  @Test
  public void missingOrAutomaticZoomSelectsAuto()
  {
    assertEquals(ClusterZoom.AUTO, ClusterZoom.parse(null));
    assertEquals(ClusterZoom.AUTO, ClusterZoom.parse("auto"));
    assertEquals(ClusterZoom.AUTO, ClusterZoom.parse(" AUTO "));
    assertEquals(ClusterZoom.AUTO, ClusterZoom.parse(""));
    assertEquals(ClusterZoom.AUTO, ClusterZoom.parse(0));
  }

  @Test
  public void acceptsFixedLevelsIncludingWideClusterViews()
  {
    for (int zoom = 1; zoom <= 20; ++zoom)
    {
      assertEquals(zoom, ClusterZoom.parse(zoom));
      assertEquals(zoom, ClusterZoom.parse(Integer.toString(zoom)));
    }
  }

  @Test
  public void rejectsInvalidLevelsInsteadOfSilentlyChangingMode()
  {
    for (Object invalid : new Object[] {-1, 21, "1.5", "NaN", "unknown"})
      assertThrows(IllegalArgumentException.class, () -> ClusterZoom.parse(invalid));
  }
}
