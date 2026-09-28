package app.organicmaps.road;

import android.view.View;
import androidx.lifecycle.Lifecycle;
import app.organicmaps.MwmActivity;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.settings.RoadDataProviderFragment;
import app.organicmaps.settings.SettingsActivity;
import com.google.android.material.snackbar.Snackbar;
import java.util.HashSet;
import java.util.Set;

public final class RoadDataPrompt
{
  private static final Set<String> SHOWN_COUNTRIES = new HashSet<>();
  private RoadDataPrompt() {}

  public static void attach(MwmActivity activity)
  {
    if (!RoadDataManager.available())
      return;
    RoadDataManager manager = RoadDataManager.get(activity);
    manager.state().observe(activity, state -> {
      if (!activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED) || !manager.enabled()
          || !manager.configured() || !state.missing() || state.busy() || RoutingController.get().isNavigating())
        return;
      View view = activity.findViewById(R.id.coordinator);
      if (view == null || !SHOWN_COUNTRIES.add(state.currentCountry()))
        return;
      Snackbar
          .make(view,
                activity.getString(R.string.road_events_missing,
                                   RoadDataProviderFragment.countryName(state.currentCountry())),
                Snackbar.LENGTH_LONG)
          .setAction(R.string.download,
                     ignored -> {
                       manager.selectCountry(state.currentCountry());
                       SettingsActivity.startForRoadDataProvider(activity);
                     })
          .show();
    });
  }
}
