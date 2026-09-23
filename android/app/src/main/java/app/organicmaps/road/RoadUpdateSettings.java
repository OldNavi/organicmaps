package app.organicmaps.road;

import android.content.SharedPreferences;
import java.util.concurrent.TimeUnit;

/** Settings are per provider; a current-country observation is shared, never inferred from imported files. */
public final class RoadUpdateSettings
{
  public static final int DEFAULT_DAYS = 7;
  static final String CURRENT_COUNTRY = "road_update_current_country";
  static final String COUNTRY_OBSERVED_AT = "road_update_country_observed_at";
  static final long COUNTRY_MAX_AGE_MS = TimeUnit.MINUTES.toMillis(30);

  private RoadUpdateSettings() {}
  public static String enabledKey(String provider)
  {
    return "road_update_enabled." + provider;
  }
  public static String intervalKey(String provider)
  {
    return "road_update_days." + provider;
  }
  static String authKey(String provider)
  {
    return "road_update_needs_login." + provider;
  }
  public static boolean enabled(SharedPreferences prefs, String provider)
  {
    return prefs.getBoolean(enabledKey(provider), false);
  }
  public static int days(SharedPreferences prefs, String provider)
  {
    return prefs.getInt(intervalKey(provider), DEFAULT_DAYS);
  }
  public static boolean needsLogin(SharedPreferences prefs, String provider)
  {
    return prefs.getBoolean(authKey(provider), false);
  }
  static boolean expired(long importedAt, long now, int days)
  {
    // Automatic updates refresh existing imports; first-time country downloads retain their explicit prompt.
    return days > 0 && importedAt > 0 && now >= importedAt && now - importedAt >= TimeUnit.DAYS.toMillis(days);
  }
  static String currentCountry(SharedPreferences prefs, long now)
  {
    long observed = prefs.getLong(COUNTRY_OBSERVED_AT, 0);
    if (observed <= 0 || now < observed || now - observed > COUNTRY_MAX_AGE_MS)
      return "";
    return prefs.getString(CURRENT_COUNTRY, "");
  }
}
