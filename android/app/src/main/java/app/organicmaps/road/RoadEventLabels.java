package app.organicmaps.road;

import android.content.Context;
import androidx.annotation.StringRes;
import app.organicmaps.R;
import app.organicmaps.sdk.road.RoadEventInfo;
import app.organicmaps.sdk.road.RoadEventKind;
import app.organicmaps.sdk.util.StringUtils;
import java.text.DateFormat;
import java.util.Date;

/** Localized text belongs to the UI, never to the database or native index. */
public final class RoadEventLabels
{
  private RoadEventLabels() {}
  @StringRes
  private static final int[] NAMES = {R.string.road_event_camera,
                                      R.string.road_event_dummy,
                                      R.string.road_event_video,
                                      R.string.road_event_red_light,
                                      R.string.road_event_lane_control,
                                      R.string.road_event_mobile,
                                      R.string.road_event_police,
                                      R.string.road_event_average_start,
                                      R.string.road_event_average_end,
                                      R.string.road_event_speed_limit,
                                      R.string.road_event_settlement_start,
                                      R.string.road_event_settlement_end,
                                      R.string.road_event_bump,
                                      R.string.road_event_crossing,
                                      R.string.road_event_children,
                                      R.string.road_event_railway,
                                      R.string.road_event_bad_road,
                                      R.string.road_event_bend,
                                      R.string.road_event_intersection,
                                      R.string.road_event_danger,
                                      R.string.road_event_no_overtaking};
  public static String name(Context context, int kind)
  {
    if (kind < 0 || kind >= RoadEventKind.COUNT)
      throw new IllegalArgumentException("Invalid event kind");
    return context.getString(NAMES[kind]);
  }

  @StringRes
  static int speedLabel(int kind)
  {
    return switch (kind)
    {
      case RoadEventKind.CAMERA, RoadEventKind.DUMMY, RoadEventKind.MOBILE, RoadEventKind.AVERAGE_START,
          RoadEventKind.AVERAGE_END, RoadEventKind.SPEED_LIMIT, RoadEventKind.SETTLEMENT_START ->
        R.string.road_event_info_limit;
      default -> R.string.road_event_info_source_speed;
    };
  }

  public static String details(Context context, RoadEventInfo event)
  {
    StringBuilder text = new StringBuilder();
    if (event.speedKmh() > 0)
    {
      var speed = StringUtils.nativeFormatSpeedAndUnits(event.speedKmh() / 3.6);
      text.append(context.getString(speedLabel(event.kind()), speed.first + " " + speed.second)).append('\n');
    }
    // Database identities use provider:country:source_id; source_id may itself contain ':'.
    String provider = event.identity().substring(0, event.identity().indexOf(':'));
    text.append(context.getString(R.string.road_event_info_source, provider));
    if (event.importedAt() > 0)
      text.append('\n').append(context.getString(
          R.string.road_event_info_updated,
          DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(event.importedAt()))));
    return text.toString();
  }
}
