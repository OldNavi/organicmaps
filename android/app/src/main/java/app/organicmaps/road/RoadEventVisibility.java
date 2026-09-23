package app.organicmaps.road;

import android.content.SharedPreferences;
import app.organicmaps.sdk.road.RoadEventKind;

/** Independent visibility sets; zoom thresholds remain in the packaged display config. */
public final class RoadEventVisibility
{
  public static final String MAP = "road_events_visible_map";
  public static final String ROUTE = "road_events_visible_route";
  private static final int DISPLAYABLE = ((1 << RoadEventKind.COUNT) - 1) & ~(1 << RoadEventKind.SETTLEMENT_END);

  private RoadEventVisibility() {}

  public static String key(boolean navigating)
  {
    return navigating ? ROUTE : MAP;
  }

  public static int get(SharedPreferences prefs, boolean navigating)
  {
    return prefs.getInt(key(navigating), DISPLAYABLE) & DISPLAYABLE;
  }

  public static void migrate(SharedPreferences prefs)
  {
    if (prefs.contains(MAP) && prefs.contains(ROUTE))
      return;
    int categories = prefs.getInt(RoadDataManager.CATEGORIES, (1 << 9) - 1);
    int kinds = 0;
    for (int kind = 0; kind < RoadEventKind.COUNT; ++kind)
    {
      int category = switch (kind)
      {
        case RoadEventKind.AVERAGE_START, RoadEventKind.AVERAGE_END -> 1;
        case RoadEventKind.SPEED_LIMIT -> 2;
        case RoadEventKind.SETTLEMENT_START, RoadEventKind.SETTLEMENT_END -> 3;
        case RoadEventKind.BUMP -> 4;
        case RoadEventKind.CROSSING, RoadEventKind.CHILDREN -> 5;
        case RoadEventKind.RAILWAY -> 6;
        case RoadEventKind.BAD_ROAD, RoadEventKind.BEND, RoadEventKind.INTERSECTION, RoadEventKind.DANGER -> 7;
        case RoadEventKind.NO_OVERTAKING -> 8;
        default -> 0;
      };
      if ((categories & (1 << category)) != 0)
        kinds |= 1 << kind;
    }
    var editor = prefs.edit();
    for (String key : new String[] {MAP, ROUTE})
      if (!prefs.contains(key))
        editor.putInt(key, kinds & DISPLAYABLE);
    editor.apply();
  }
}
