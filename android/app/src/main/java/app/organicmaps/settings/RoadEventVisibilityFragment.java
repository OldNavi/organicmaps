package app.organicmaps.settings;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.SwitchPreferenceCompat;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.road.RoadDataManager;
import app.organicmaps.road.RoadEventLabels;
import app.organicmaps.road.RoadEventVisibility;
import app.organicmaps.sdk.road.RoadEventKind;

public final class RoadEventVisibilityFragment extends BaseXmlSettingsFragment
{
  public static final String ON_ROUTE = "on_route";

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
    getPreferenceScreen().removeAll();
    var context = requireContext();
    var prefs = MwmApplication.prefs(context);
    boolean navigating = requireArguments().getBoolean(ON_ROUTE);
    for (int kind = 0; kind < RoadEventKind.COUNT; ++kind)
    {
      if (kind == RoadEventKind.SETTLEMENT_END)
        continue;
      int bit = 1 << kind;
      var toggle = new SwitchPreferenceCompat(context);
      toggle.setTitle(RoadEventLabels.name(context, kind));
      toggle.setPersistent(false);
      toggle.setChecked((RoadEventVisibility.get(prefs, navigating) & bit) != 0);
      toggle.setOnPreferenceChangeListener((preference, value) -> {
        int mask = RoadEventVisibility.get(prefs, navigating);
        prefs.edit().putInt(RoadEventVisibility.key(navigating), (Boolean) value ? mask | bit : mask & ~bit).apply();
        RoadDataManager.get(context).configure();
        return true;
      });
      getPreferenceScreen().addPreference(toggle);
    }
  }
}
