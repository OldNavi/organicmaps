package app.organicmaps.sdk.road;

/** Stable database/JNI identifiers. Keep values synchronized with routing::RoadEventKind. */
public final class RoadEventKind
{
  public static final int CAMERA = 0;
  public static final int DUMMY = 1;
  public static final int VIDEO = 2;
  public static final int RED_LIGHT = 3;
  public static final int LANE_CONTROL = 4;
  public static final int MOBILE = 5;
  public static final int POLICE = 6;
  public static final int AVERAGE_START = 7;
  public static final int AVERAGE_END = 8;
  public static final int SPEED_LIMIT = 9;
  public static final int SETTLEMENT_START = 10;
  public static final int SETTLEMENT_END = 11;
  public static final int BUMP = 12;
  public static final int CROSSING = 13;
  public static final int CHILDREN = 14;
  public static final int RAILWAY = 15;
  public static final int BAD_ROAD = 16;
  public static final int BEND = 17;
  public static final int INTERSECTION = 18;
  public static final int DANGER = 19;
  public static final int NO_OVERTAKING = 20;
  public static final int COUNT = 21;

  private RoadEventKind() {}
  public static boolean isCamera(int kind)
  {
    return kind >= CAMERA && kind <= AVERAGE_END;
  }
}
