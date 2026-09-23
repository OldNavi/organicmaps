package app.organicmaps.road;

import android.content.Context;
import app.organicmaps.sdk.road.RoadEventKind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** Build-time display thresholds; not provider data and not user preferences. */
final class RoadEventDisplayConfig
{
  private static final String ASSET = "road_event_display.json";
  // Stable RoadEventKind order, independent of provider-specific formats and categories.
  private static final String[] KINDS = {
      "camera",       "dummy",         "video",        "red_light",   "lane_control",     "mobile",
      "police",       "average_start", "average_end",  "speed_limit", "settlement_start", "settlement_end",
      "bump",         "crossing",      "children",     "railway",     "bad_road",         "bend",
      "intersection", "danger",        "no_overtaking"};

  private RoadEventDisplayConfig() {}

  static int[] load(Context context)
  {
    try (var stream = context.getAssets().open(ASSET); var bytes = new ByteArrayOutputStream())
    {
      byte[] buffer = new byte[1024];
      int count;
      while ((count = stream.read(buffer)) != -1)
        bytes.write(buffer, 0, count);
      JSONObject config = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
      if (KINDS.length != RoadEventKind.COUNT || config.length() != KINDS.length)
        throw new IllegalStateException("Road-event display config must contain every kind exactly once");
      int[] zooms = new int[KINDS.length];
      for (int kind = 0; kind < zooms.length; ++kind)
      {
        Object value = config.get(KINDS[kind]);
        if (!(value instanceof Integer zoom) || zoom < 1 || zoom > 20)
          throw new IllegalStateException("Invalid minimum zoom for " + KINDS[kind]);
        zooms[kind] = zoom;
      }
      return zooms;
    }
    catch (IOException | JSONException e)
    {
      throw new IllegalStateException("Invalid packaged road-event display config", e);
    }
  }
}
