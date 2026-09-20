package app.organicmaps.util;

import static org.junit.Assert.*;

import android.content.res.Configuration;
import app.organicmaps.sdk.util.Config;
import org.junit.Test;

public class ThemeSwitcherTest
{
  @Test
  public void systemThemeFollowsNightBitInCarAndNormalModes()
  {
    for (int type : new int[] {Configuration.UI_MODE_TYPE_CAR, Configuration.UI_MODE_TYPE_NORMAL})
    {
      assertTrue(ThemeSwitcher.isDarkTheme(Config.UiTheme.SYSTEM, type | Configuration.UI_MODE_NIGHT_YES));
      assertFalse(ThemeSwitcher.isDarkTheme(Config.UiTheme.SYSTEM, type | Configuration.UI_MODE_NIGHT_NO));
    }
  }

  @Test
  public void explicitAndScheduledResultsOverrideSystemTheme()
  {
    assertFalse(ThemeSwitcher.isDarkTheme(Config.UiTheme.LIGHT, Configuration.UI_MODE_NIGHT_YES));
    assertTrue(ThemeSwitcher.isDarkTheme(Config.UiTheme.DARK, Configuration.UI_MODE_NIGHT_NO));
  }
}
