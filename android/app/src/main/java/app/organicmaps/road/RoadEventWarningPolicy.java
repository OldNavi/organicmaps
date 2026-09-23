package app.organicmaps.road;

import app.organicmaps.sdk.road.RoadEventAhead;
import app.organicmaps.sdk.road.RoadEventKind;
import app.organicmaps.settings.SpeedWarningSettings;
import java.util.ArrayList;

/** Pure warning policy: moving, visible, relevant to the selected level, and not already announced on this approach. */
final class RoadEventWarningPolicy
{
  private static final long LEAVE_DELAY_MS = 30_000;
  private final ArrayList<Announced> mAnnounced = new ArrayList<>();

  static boolean isImportant(int kind)
  {
    return switch (kind)
    {
      case RoadEventKind.CAMERA, RoadEventKind.MOBILE, RoadEventKind.AVERAGE_START, RoadEventKind.AVERAGE_END,
          RoadEventKind.LANE_CONTROL, RoadEventKind.RED_LIGHT, RoadEventKind.CHILDREN, RoadEventKind.BUMP ->
        true;
      default -> false;
    };
  }

  static boolean isEligible(RoadEventAhead event, int level, int visibleKinds, double speedMps, int offsetKmh)
  {
    if (level == SpeedWarningSettings.OFF || !Double.isFinite(speedMps) || speedMps < 1
        || (visibleKinds & (1 << event.kind())) == 0
        || (level == SpeedWarningSettings.IMPORTANT && !isImportant(event.kind())))
      return false;
    if (event.kind() == RoadEventKind.BUMP)
      return event.speedMps() > 0 && speedMps > event.speedMps() + 10.0 / 3.6 + 1e-6;
    if (level == SpeedWarningSettings.IMPORTANT && isSpeedCamera(event.kind()) && event.speedMps() > 0)
      return speedMps > event.speedMps() + offsetKmh / 3.6 + 1e-6;
    return true;
  }

  void expire(long now)
  {
    mAnnounced.removeIf(item -> now - item.lastSeen > LEAVE_DELAY_MS);
  }

  void observe(RoadEventAhead event, long now)
  {
    for (var item : mAnnounced)
      if (sameEvent(item.event, event))
        item.lastSeen = now;
  }

  boolean wasAnnounced(RoadEventAhead event)
  {
    for (var item : mAnnounced)
      if (sameEvent(item.event, event))
        return true;
    return false;
  }

  void announced(RoadEventAhead event, long now)
  {
    if (mAnnounced.size() == 128)
      mAnnounced.remove(0);
    mAnnounced.add(new Announced(event, now));
  }

  private static boolean isSpeedCamera(int kind)
  {
    return kind == RoadEventKind.CAMERA || kind == RoadEventKind.MOBILE || kind == RoadEventKind.AVERAGE_START
 || kind == RoadEventKind.AVERAGE_END;
  }

  private static boolean sameEvent(RoadEventAhead a, RoadEventAhead b)
  {
    boolean sameKind = a.kind() == b.kind();
    boolean nativeDuplicate =
        (a.identity().isEmpty() || b.identity().isEmpty()) && isSpeedCamera(a.kind()) && isSpeedCamera(b.kind());
    if (!sameKind && !nativeDuplicate)
      return false;
    if (!nativeDuplicate && !a.identity().equals(b.identity()))
      return false;
    double north = (a.latitude() - b.latitude()) * 111_320;
    double east = (a.longitude() - b.longitude()) * 111_320 * Math.cos(Math.toRadians(a.latitude()));
    double radius = nativeDuplicate ? 50 : 5;
    return north * north + east * east <= radius * radius;
  }

  private static final class Announced
  {
    final RoadEventAhead event;
    long lastSeen;
    Announced(RoadEventAhead event, long now)
    {
      this.event = event;
      lastSeen = now;
    }
  }
}
