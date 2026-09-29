package app.organicmaps;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.Configuration;
import org.junit.Test;

public class MwmActivityConfigurationTest
{
  private static final int DAY = Configuration.UI_MODE_NIGHT_NO;
  private static final int NIGHT = Configuration.UI_MODE_NIGHT_YES;
  private static final int PHONE = Configuration.UI_MODE_TYPE_NORMAL;
  private static final int CAR = Configuration.UI_MODE_TYPE_CAR;

  @Test
  public void automotiveThemeChangesPreserveMapSurface()
  {
    for (int oldType : new int[] {PHONE, CAR})
      for (int newType : new int[] {PHONE, CAR})
      {
        assertFalse(MwmActivity.shouldRecreateForUiMode(oldType | DAY, newType | NIGHT, true));
        assertFalse(MwmActivity.shouldRecreateForUiMode(oldType | NIGHT, newType | DAY, true));
      }
  }

  @Test
  public void standardThemeChangesStillRecreateViews()
  {
    assertTrue(MwmActivity.shouldRecreateForUiMode(PHONE | DAY, PHONE | NIGHT, false));
    assertTrue(MwmActivity.shouldRecreateForUiMode(PHONE | DAY, CAR | NIGHT, false));
    assertTrue(MwmActivity.shouldRecreateForUiMode(CAR | NIGHT, PHONE | DAY, false));
  }

  @Test
  public void redundantUpdatesAndCarModeOnlyDoNotRecreate()
  {
    for (boolean automotive : new boolean[] {true, false})
    {
      assertFalse(MwmActivity.shouldRecreateForUiMode(PHONE | DAY, CAR | DAY, automotive));
      assertFalse(MwmActivity.shouldRecreateForUiMode(CAR | NIGHT, PHONE | NIGHT, automotive));
      assertFalse(MwmActivity.shouldRecreateForUiMode(CAR | NIGHT, CAR | NIGHT, automotive));
    }
  }
}
