package app.organicmaps.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.road.RoadDataManager;

public final class RoadDataSettingsFragment extends BaseXmlSettingsFragment
{
  private RoadDataManager mManager;
  private Preference mMapVisibility;
  private Preference mRouteVisibility;
  private Preference mProvider;

  @Override
  protected int getXmlResources()
  {
    return R.xml.prefs_road_events;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle state)
  {
    super.onViewCreated(view, state);
    if (!RoadDataManager.available())
      return;
    mManager = RoadDataManager.get(requireContext());
    getPreferenceScreen().removeAll();
    Context context = requireContext();
    SwitchPreferenceCompat enabled = new SwitchPreferenceCompat(context);
    enabled.setTitle(R.string.road_events_enabled);
    enabled.setPersistent(false);
    enabled.setChecked(mManager.enabled());
    enabled.setOnPreferenceChangeListener((pref, value) -> {
      MwmApplication.prefs(context).edit().putBoolean(RoadDataManager.ENABLED, (Boolean) value).apply();
      mManager.configure();
      update(mManager.state().getValue());
      return true;
    });
    getPreferenceScreen().addPreference(enabled);
    mMapVisibility = addVisibilityMenu(false, R.string.road_events_show_map);
    mRouteVisibility = addVisibilityMenu(true, R.string.road_events_show_route);
    SwitchPreferenceCompat coverage = new SwitchPreferenceCompat(context);
    coverage.setKey(RoadDataManager.COVERAGE);
    coverage.setTitle(R.string.road_events_coverage);
    coverage.setPersistent(false);
    coverage.setChecked(mManager.coverageVisible());
    coverage.setOnPreferenceChangeListener((pref, value) -> {
      MwmApplication.prefs(context).edit().putBoolean(RoadDataManager.COVERAGE, (Boolean) value).apply();
      mManager.configure();
      return true;
    });
    getPreferenceScreen().addPreference(coverage);
    PreferenceCategory providers = new PreferenceCategory(context);
    providers.setTitle(R.string.road_events_providers);
    getPreferenceScreen().addPreference(providers);
    mProvider = new Preference(context);
    mProvider.setKey("road_events_provider_" + mManager.providerId());
    mProvider.setTitle(mManager.providerId());
    mProvider.setFragment(RoadDataProviderFragment.class.getName());
    providers.addPreference(mProvider);
    mManager.state().observe(getViewLifecycleOwner(), this::update);
  }

  private Preference addVisibilityMenu(boolean navigating, int title)
  {
    Preference menu = new Preference(requireContext());
    menu.setTitle(title);
    menu.setFragment(RoadEventVisibilityFragment.class.getName());
    menu.getExtras().putBoolean(RoadEventVisibilityFragment.ON_ROUTE, navigating);
    getPreferenceScreen().addPreference(menu);
    return menu;
  }

  private void update(RoadDataManager.State state)
  {
    if (state == null)
      return;
    mMapVisibility.setEnabled(mManager.enabled());
    mRouteVisibility.setEnabled(mManager.enabled());
    mProvider.setSummary(mManager.configured() ? R.string.road_events_signed_in : R.string.not_signed_in);
  }
}
