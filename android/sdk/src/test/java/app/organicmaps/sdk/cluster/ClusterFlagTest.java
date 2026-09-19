package app.organicmaps.sdk.cluster;

import static org.junit.Assert.*;

import org.junit.Test;

public class ClusterFlagTest
{
  private static final String[] FLAGS = {"poi", "3d"};

  @Test
  public void omissionAndZeroDisableFlags()
  {
    for (String flag : FLAGS)
    {
      assertFalse(ClusterFlag.parse(flag, null));
      assertFalse(ClusterFlag.parse(flag, "0"));
      assertFalse(ClusterFlag.parse(flag, 0));
    }
  }

  @Test
  public void oneEnablesFlags()
  {
    for (String flag : FLAGS)
    {
      assertTrue(ClusterFlag.parse(flag, "1"));
      assertTrue(ClusterFlag.parse(flag, 1));
    }
  }

  @Test
  public void acceptsBooleanBundleValues()
  {
    for (String flag : FLAGS)
    {
      assertTrue(ClusterFlag.parse(flag, true));
      assertFalse(ClusterFlag.parse(flag, false));
    }
  }

  @Test
  public void rejectsMistypedFlagsAndNamesTheInvalidOption()
  {
    for (String flag : FLAGS)
      for (Object value : new Object[] {"", "auto", "true", 2, -1, 1.5})
      {
        var error = assertThrows(IllegalArgumentException.class, () -> ClusterFlag.parse(flag, value));
        assertTrue(error.getMessage().contains(flag));
      }
  }
}
