package app.organicmaps.sdk.cluster;

import static org.junit.Assert.*;

import org.junit.Test;

public class ClusterCameraTest
{
  @Test
  public void omissionRestoresExistingDefaults()
  {
    var options = ClusterCamera.parse(null, null, null, null);
    assertEquals(-1.0, options.tilt, 0);
    assertEquals(0.5, options.anchorX, 0);
    assertEquals(0.75, options.anchorY, 0);
  }

  @Test
  public void acceptsTiltBoundariesAndAutomaticMode()
  {
    assertEquals(0.0, ClusterCamera.parse("0", null, null, null).tilt, 0);
    assertEquals(55.0, ClusterCamera.parse(55, null, null, null).tilt, 0);
    assertEquals(-1.0, ClusterCamera.parse("AUTO", null, null, null).tilt, 0);
  }

  @Test
  public void supportsCompactAndSeparateAnchors()
  {
    var pair = ClusterCamera.parse(45, "0.3, 0.85", null, null);
    var separate = ClusterCamera.parse("45", null, 0.3, "0.85");
    assertEquals(pair.anchorX, separate.anchorX, 0);
    assertEquals(pair.anchorY, separate.anchorY, 0);
    assertEquals(0.5, ClusterCamera.parse(null, null, null, 1).anchorX, 0);
    assertEquals(0.0, ClusterCamera.parse(null, "0,1", null, null).anchorX, 0);
  }

  @Test
  public void rejectsInvalidOrAmbiguousCameraOptions()
  {
    for (Object angle : new Object[] {-1, 56, Double.NaN, Double.POSITIVE_INFINITY, ""})
      assertThrows(IllegalArgumentException.class, () -> ClusterCamera.parse(angle, null, null, null));
    for (String anchor : new String[] {"", "0.5", "0.5,", "0,1,0", "-0.1,0.5", "0.5,1.1", "NaN,0.5"})
      assertThrows(IllegalArgumentException.class, () -> ClusterCamera.parse(null, anchor, null, null));
    assertThrows(IllegalArgumentException.class, () -> ClusterCamera.parse(null, "0.5,0.75", 0.5, null));
    assertThrows(IllegalArgumentException.class, () -> ClusterCamera.parse(null, null, Double.NEGATIVE_INFINITY, 0.5));
  }
}
