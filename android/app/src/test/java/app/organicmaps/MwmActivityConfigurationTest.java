package app.organicmaps;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.Configuration;
import org.junit.Test;

public class MwmActivityConfigurationTest
{
  @Test
  public void carModeOnlyPreservesMapActivity()
  {
    assertFalse(MwmActivity.shouldRecreateForUiMode(Configuration.UI_MODE_TYPE_NORMAL | Configuration.UI_MODE_NIGHT_NO,
                                                    Configuration.UI_MODE_TYPE_CAR | Configuration.UI_MODE_NIGHT_NO));
  }

  @Test
  public void enteringCarModeAtNightRecreatesSearchViews()
  {
    assertTrue(MwmActivity.shouldRecreateForUiMode(Configuration.UI_MODE_TYPE_NORMAL | Configuration.UI_MODE_NIGHT_NO,
                                                   Configuration.UI_MODE_TYPE_CAR | Configuration.UI_MODE_NIGHT_YES));
    assertTrue(MwmActivity.shouldRecreateForUiMode(Configuration.UI_MODE_TYPE_CAR | Configuration.UI_MODE_NIGHT_YES,
                                                   Configuration.UI_MODE_TYPE_NORMAL | Configuration.UI_MODE_NIGHT_NO));
  }

  @Test
  public void themeAndOtherConfigurationChangesStillRecreate()
  {
    assertTrue(MwmActivity.shouldRecreateForUiMode(Configuration.UI_MODE_TYPE_CAR | Configuration.UI_MODE_NIGHT_NO,
                                                   Configuration.UI_MODE_TYPE_CAR | Configuration.UI_MODE_NIGHT_YES));
    assertTrue(MwmActivity.shouldRecreateForUiMode(Configuration.UI_MODE_TYPE_CAR | Configuration.UI_MODE_NIGHT_YES,
                                                   Configuration.UI_MODE_TYPE_CAR | Configuration.UI_MODE_NIGHT_YES));
  }
}
