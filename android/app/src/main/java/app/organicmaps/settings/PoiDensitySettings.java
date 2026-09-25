package app.organicmaps.settings;

import android.content.Context;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import app.organicmaps.R;
import app.organicmaps.sdk.rendering.PoiDensity;
import app.organicmaps.sdk.util.Config;

final class PoiDensitySettings
{
  private PoiDensitySettings() {}

  static void addPreferences(PreferenceFragmentCompat fragment)
  {
    if (!Config.isAuto())
      return;
    Context context = fragment.requireContext();
    PreferenceCategory general = fragment.findPreference(context.getString(R.string.pref_settings_general));
    if (general == null)
      return;
    for (boolean cluster : new boolean[] {false, true})
    {
      String key = cluster ? "poi_density_cluster" : "poi_density_main";
      if (general.findPreference(key) != null)
        continue;
      ListPreference preference = new ListPreference(context);
      preference.setKey(key);
      preference.setTitle(cluster ? R.string.poi_density_cluster : R.string.poi_density_main);
      preference.setOrder(cluster ? 25 : 24);
      preference.setPersistent(false);
      preference.setEntries(new CharSequence[] {context.getString(R.string.poi_density_low),
                                                context.getString(R.string.poi_density_normal),
                                                context.getString(R.string.poi_density_high)});
      preference.setEntryValues(new CharSequence[] {
          Integer.toString(PoiDensity.LOW), Integer.toString(PoiDensity.NORMAL), Integer.toString(PoiDensity.HIGH)});
      preference.setValue(Integer.toString(PoiDensity.nativeGet(cluster)));
      preference.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
      preference.setOnPreferenceChangeListener((pref, value) -> {
        PoiDensity.nativeSet(cluster, Integer.parseInt((String) value));
        return true;
      });
      general.addPreference(preference);
    }
  }
}
