package app.organicmaps.cluster;

import android.content.Context;
import android.net.Uri;
import app.organicmaps.sdk.bookmarks.data.BookmarkInfo;
import app.organicmaps.sdk.bookmarks.data.BookmarkManager;
import app.organicmaps.sdk.util.concurrency.UiThread;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Main-thread bookmark snapshots keep Binder queries away from the native bookmark manager. */
public final class VoiceSavedPlaces
{
  public static final class Place
  {
    public final long id;
    public final String title;
    public final double latitude;
    public final double longitude;
    Place(BookmarkInfo bookmark)
    {
      id = bookmark.getBookmarkId();
      title = bookmark.getName();
      latitude = bookmark.getLat();
      longitude = bookmark.getLon();
    }
  }

  private static volatile List<Place> sPlaces = Collections.emptyList();
  private static boolean sInitialized;

  public static void initialize(Context context)
  {
    if (sInitialized)
      return;
    sInitialized = true;
    BookmarkManager.INSTANCE.addCategoriesUpdatesListener(() -> UiThread.runLater(() -> refresh(context)));
    refresh(context);
  }

  private static void refresh(Context context)
  {
    List<Place> places = new ArrayList<>();
    for (var category : BookmarkManager.INSTANCE.getCategories())
      for (long id : category.getBookmarkIds())
      {
        BookmarkInfo bookmark = BookmarkManager.INSTANCE.getBookmarkInfo(id);
        if (bookmark != null)
          places.add(new Place(bookmark));
      }
    sPlaces = Collections.unmodifiableList(places);
    context.getContentResolver().notifyChange(Uri.withAppendedPath(NavigationProvider.CONTENT_URI, "bookmarks"), null);
  }

  public static List<Place> get()
  {
    return sPlaces;
  }

  public static Place resolve(String id, String homeName, String workName)
  {
    String name = "home".equals(id) ? homeName : "work".equals(id) ? workName : null;
    Place match = null;
    for (Place place : sPlaces)
    {
      boolean matches = name == null ? Long.toString(place.id).equals(id)
                                     : place.title.equalsIgnoreCase(name) || place.title.equalsIgnoreCase(id);
      if (matches)
      {
        if (match != null)
          return null;
        match = place;
      }
    }
    return match;
  }
}
