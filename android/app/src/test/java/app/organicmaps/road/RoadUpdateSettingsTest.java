package app.organicmaps.road;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.SharedPreferences;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class RoadUpdateSettingsTest
{
  @Test
  public void expiryUsesSuccessfulImportAndConfiguredDays()
  {
    long imported = 1_790_000_000_000L;
    long week = TimeUnit.DAYS.toMillis(7);
    assertFalse(RoadUpdateSettings.expired(imported, imported + week - 1, 7));
    assertTrue(RoadUpdateSettings.expired(imported, imported + week, 7));
    assertFalse(RoadUpdateSettings.expired(imported, imported + week, 10));
    assertTrue(RoadUpdateSettings.expired(imported, imported + week, 1));
    assertFalse(RoadUpdateSettings.expired(0, imported + week, 7));
    assertFalse(RoadUpdateSettings.expired(imported, imported - 1, 7));
    assertFalse(RoadUpdateSettings.expired(imported, Long.MAX_VALUE, 0));
    assertFalse(RoadUpdateSettings.expired(imported, Long.MAX_VALUE, -1));
  }

  @Test
  public void providerSettingsAreIndependentAndDefaultToDisabledAndSevenDays()
  {
    SharedPreferences prefs = mock(SharedPreferences.class);
    when(prefs.getInt(RoadUpdateSettings.intervalKey("a"), 7)).thenReturn(7);
    when(prefs.getInt(RoadUpdateSettings.intervalKey("b"), 7)).thenReturn(3);
    when(prefs.getBoolean(RoadUpdateSettings.enabledKey("b"), false)).thenReturn(true);
    assertFalse(RoadUpdateSettings.enabled(prefs, "a"));
    assertEquals(7, RoadUpdateSettings.days(prefs, "a"));
    assertTrue(RoadUpdateSettings.enabled(prefs, "b"));
    assertEquals(3, RoadUpdateSettings.days(prefs, "b"));
    assertNotEquals(RoadUpdateSettings.authKey("a"), RoadUpdateSettings.authKey("b"));
  }

  @Test
  public void currentCountryRequiresAnUnexpiredObservationAndRejectsClockRollback()
  {
    SharedPreferences prefs = mock(SharedPreferences.class);
    long seen = 1_790_000_000_000L;
    when(prefs.getLong(RoadUpdateSettings.COUNTRY_OBSERVED_AT, 0)).thenReturn(seen);
    when(prefs.getString(RoadUpdateSettings.CURRENT_COUNTRY, "")).thenReturn("RU");
    assertEquals("RU", RoadUpdateSettings.currentCountry(prefs, seen));
    assertEquals("RU", RoadUpdateSettings.currentCountry(prefs, seen + RoadUpdateSettings.COUNTRY_MAX_AGE_MS));
    assertEquals("", RoadUpdateSettings.currentCountry(prefs, seen + RoadUpdateSettings.COUNTRY_MAX_AGE_MS + 1));
    assertEquals("", RoadUpdateSettings.currentCountry(prefs, seen - 1));
    when(prefs.getLong(RoadUpdateSettings.COUNTRY_OBSERVED_AT, 0)).thenReturn(0L);
    assertEquals("", RoadUpdateSettings.currentCountry(prefs, seen));
  }
}
