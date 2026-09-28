package app.organicmaps.settings;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;
import app.organicmaps.BuildConfig;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;

public final class SpeedWarningSettings
{
  public static final String LEVEL_KEY = "auto_road_warning_level";
  public static final int OFF = 0;
  public static final int IMPORTANT = 1;
  public static final int ALL = 2;
  public static final String OFFSET_KEY = "auto_speed_warning_offset_kmh";
  public static final String MODE_KEY = "auto_speed_warning_mode";
  public static final String VOICE = "voice";
  public static final String SOUND = "sound";
  public static final String SILENT = "silent";
  public static final int MAX_OFFSET_KMH = 40;

  private SpeedWarningSettings() {}

  public static boolean isAvailable()
  {
    return "auto".equals(BuildConfig.FLAVOR);
  }

  public static int offsetKmh(Context context)
  {
    return isAvailable() ? Math.max(0, Math.min(MAX_OFFSET_KMH, MwmApplication.prefs(context).getInt(OFFSET_KEY, 0)))
                         : 0;
  }

  public static String mode(Context context)
  {
    String mode = MwmApplication.prefs(context).getString(MODE_KEY, VOICE);
    return SILENT.equals(mode) ? VOICE : mode;
  }

  public static int level(Context context)
  {
    SharedPreferences prefs = MwmApplication.prefs(context);
    return prefs.getInt(LEVEL_KEY, SILENT.equals(prefs.getString(MODE_KEY, VOICE)) ? OFF : IMPORTANT);
  }

  public static void configureLevelPreference(ListPreference preference, Context context)
  {
    preference.setKey(LEVEL_KEY);
    preference.setTitle(R.string.road_warning_level);
    preference.setPersistent(false);
    preference.setEntries(new CharSequence[] {context.getString(R.string.road_warning_off),
                                              context.getString(R.string.road_warning_important),
                                              context.getString(R.string.road_warning_all)});
    preference.setEntryValues(new CharSequence[] {"0", "1", "2"});
    preference.setValue(Integer.toString(level(context)));
    preference.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
    preference.setOnPreferenceChangeListener((pref, value) -> {
      MwmApplication.prefs(context).edit().putInt(LEVEL_KEY, Integer.parseInt((String) value)).apply();
      return true;
    });
  }

  public static void refreshLevel(PreferenceFragmentCompat fragment)
  {
    if (!isAvailable())
      return;
    ListPreference preference = fragment.findPreference(LEVEL_KEY);
    if (preference != null)
      preference.setValue(Integer.toString(level(fragment.requireContext())));
  }

  public static void addPreferences(PreferenceFragmentCompat fragment)
  {
    if (!isAvailable())
      return;
    Context context = fragment.requireContext();
    PreferenceCategory general = fragment.findPreference(context.getString(R.string.pref_settings_general));
    if (general == null || general.findPreference(OFFSET_KEY) != null)
      return;
    SharedPreferences prefs = MwmApplication.prefs(context);
    SeekBarPreference offset = new SeekBarPreference(context);
    offset.setKey(OFFSET_KEY);
    offset.setTitle(R.string.auto_speed_warning_title);
    offset.setOrder(21);
    offset.setPersistent(false);
    offset.setMin(0);
    offset.setMax(MAX_OFFSET_KMH);
    offset.setSeekBarIncrement(1);
    offset.setUpdatesContinuously(true);
    offset.setValue(offsetKmh(context));
    offset.setSummary(context.getString(R.string.auto_speed_warning_offset, offset.getValue()));
    offset.setOnPreferenceChangeListener((preference, value) -> {
      int offsetKmh = (Integer) value;
      prefs.edit().putInt(OFFSET_KEY, offsetKmh).apply();
      preference.setSummary(context.getString(R.string.auto_speed_warning_offset, offsetKmh));
      return true;
    });
    general.addPreference(offset);

    ListPreference level = new ListPreference(context);
    configureLevelPreference(level, context);
    level.setOrder(22);
    general.addPreference(level);

    ListPreference mode = new ListPreference(context);
    mode.setKey(MODE_KEY);
    mode.setTitle(R.string.road_warning_delivery);
    mode.setOrder(23);
    mode.setPersistent(false);
    mode.setEntries(new CharSequence[] {context.getString(R.string.auto_speed_warning_mode_voice),
                                        context.getString(R.string.auto_speed_warning_mode_sound)});
    mode.setEntryValues(new CharSequence[] {VOICE, SOUND});
    mode.setValue(mode(context));
    mode.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
    mode.setOnPreferenceChangeListener((preference, value) -> {
      prefs.edit().putString(MODE_KEY, (String) value).apply();
      return true;
    });
    general.addPreference(mode);
  }
}
