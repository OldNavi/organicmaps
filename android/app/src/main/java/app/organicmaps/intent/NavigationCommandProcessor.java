package app.organicmaps.intent;

import android.content.Intent;
import androidx.annotation.NonNull;
import app.organicmaps.MwmActivity;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.cluster.VoiceSavedPlaces;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.bookmarks.data.BookmarkManager;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.search.SearchEngine;
import app.organicmaps.sdk.util.concurrency.UiThread;
import app.organicmaps.sdk.util.log.Logger;
import com.google.android.material.snackbar.Snackbar;
import java.lang.ref.WeakReference;

public final class NavigationCommandProcessor implements IntentProcessor
{
  private static long sRequestGeneration;

  public static void cancelPendingRequests()
  {
    ++sRequestGeneration;
    RoutingController.get().cancelPendingAutoStart();
  }
  @Override
  public boolean process(@NonNull Intent intent, @NonNull MwmActivity activity)
  {
    if (!Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null)
      return false;
    final NavigationCommand command;
    try
    {
      command = NavigationCommand.parse(intent.getData().toString());
    }
    catch (IllegalArgumentException e)
    {
      Logger.w("NavigationCommand", e.getMessage());
      return false;
    }
    if (command == null)
      return false;
    long generation = ++sRequestGeneration;
    RoutingController.get().cancelPendingAutoStart();
    if (!command.search && command.place != null && BookmarkManager.INSTANCE.isAsyncBookmarksLoadingInProgress())
    {
      WeakReference<MwmActivity> owner = new WeakReference<>(activity);
      BookmarkManager.INSTANCE.addLoadingListener(new BookmarkManager.BookmarksLoadingListener() {
        @Override
        public void onBookmarksLoadingFinished()
        {
          UiThread.runLater(() -> {
            BookmarkManager.INSTANCE.removeLoadingListener(this);
            MwmActivity target = owner.get();
            if (generation == sRequestGeneration && target != null && !target.isFinishing() && !target.isDestroyed())
              process(intent, target);
          });
        }
      });
      return true;
    }
    if (command.alongRoute)
    {
      Snackbar
          .make(activity.findViewById(android.R.id.content), R.string.voice_navigation_along_route_unavailable,
                Snackbar.LENGTH_LONG)
          .show();
      return true;
    }
    if (command.search)
    {
      SearchEngine.INSTANCE.cancelInteractiveSearch();
      if (command.center != null)
      {
        Framework.nativeStopLocationFollow();
        Framework.nativeSetViewportCenter(command.center.latitude, command.center.longitude, 16);
        Framework.nativeSetSearchViewport(command.center.latitude, command.center.longitude, 16);
      }
      activity.showSearch(command.text, command.locale, false);
      return true;
    }

    final MapObject destination;
    if (command.destination != null)
      destination = MapObject.createMapObject(MapObject.API_POINT, command.title, "", command.destination.latitude,
                                              command.destination.longitude);
    else
    {
      var place = VoiceSavedPlaces.resolve(command.place, activity.getString(R.string.voice_navigation_home_name),
                                           activity.getString(R.string.voice_navigation_work_name));
      if (place == null)
      {
        Snackbar
            .make(activity.findViewById(android.R.id.content), R.string.voice_navigation_saved_place_missing,
                  Snackbar.LENGTH_LONG)
            .show();
        return true;
      }
      destination = MapObject.createMapObject(MapObject.API_POINT, place.title, "", place.latitude, place.longitude);
    }
    MapObject origin =
        command.origin == null
            ? MwmApplication.from(activity).getLocationHelper().getMyPosition()
            : MapObject.createMapObject(MapObject.API_POINT, "", "", command.origin.latitude, command.origin.longitude);
    SearchEngine.INSTANCE.cancelInteractiveSearch();
    activity.forceCloseSearchFragment();
    RoutingController.get().prepare(origin, destination, Router.Vehicle, command.startGuidance);
    return true;
  }
}
