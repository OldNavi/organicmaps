package app.organicmaps.road;

import app.organicmaps.sdk.road.RoadEventBatch;
import app.organicmaps.sdk.road.RoadEventKind;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Arrays;

/** Converts the source's delimited export into provider-independent numeric events before SQLite. */
final class OpenSpeedCamImporter implements RoadEventImporter
{
  private static final int BATCH_SIZE = 512;
  private static final int MAX_EVENTS = 2_000_000;
  private static final long MAX_CHARACTERS = 128L * 1024 * 1024;
  private BufferedReader mReader;
  private int mLine;
  private int mCount;
  private long mCharacters;
  private boolean mHeader;
  private boolean mDone;

  @Override
  public void open(InputStream input)
  {
    mReader = new BufferedReader(new InputStreamReader(input, Charset.forName("windows-1251")));
  }

  @Override
  public RoadEventBatch nextBatch() throws IOException
  {
    if (mDone)
      return null;
    double[] values = new double[BATCH_SIZE * RoadEventBatch.WIDTH];
    String[] identities = new String[BATCH_SIZE];
    int count = 0;
    String line;
    while (count < BATCH_SIZE && (line = mReader.readLine()) != null)
    {
      ++mLine;
      mCharacters += line.length();
      if (line.length() > 16384 || mCharacters > MAX_CHARACTERS)
        throw invalid();
      line = line.trim();
      if (line.isEmpty())
        continue;
      if (mLine == 1 && line.startsWith("IDX,X,Y,TYPE,SPEED,DIRTYPE,DIRECTION,DISTANCE,ANGLE"))
      {
        mHeader = true;
        continue;
      }
      if (++mCount > MAX_EVENTS)
        throw invalid();
      parse(line, values, count * RoadEventBatch.WIDTH, identities, count);
      ++count;
    }
    if (count < BATCH_SIZE)
    {
      mDone = true;
      if (mCount == 0 && !mHeader)
        throw invalid();
    }
    if (count == 0)
      return null;
    return new RoadEventBatch(Arrays.copyOf(values, count * RoadEventBatch.WIDTH), Arrays.copyOf(identities, count));
  }

  private void parse(String line, double[] values, int offset, String[] identities, int row) throws IOException
  {
    int comment = line.indexOf("//");
    if (comment < 0)
      throw invalid();
    String[] fields = line.substring(0, comment).split(",", -1);
    if (fields.length != 9)
      throw invalid();
    String[] description = line.substring(comment + 2).split("\\|", 3);
    if (description.length < 2)
      throw invalid();
    String identity = description[0].trim();
    if (identity.isEmpty() || identity.length() > 128)
      throw invalid();
    try
    {
      double index = integer(fields[0], 0xFFFFFFFFL);
      double lon = Double.parseDouble(fields[1].trim());
      double lat = Double.parseDouble(fields[2].trim());
      if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 85 || Math.abs(lon) > 180)
        throw invalid();
      int kind = kind((int) integer(fields[3], 1000), description[1].trim());
      double speed = integer(fields[4], 400);
      double directionType = integer(fields[5], 2);
      // Reverse the source azimuth to point from the approach sector towards the event.
      double direction = (integer(fields[6], 360) + 180) % 360;
      double distance = integer(fields[7], 65535);
      double angle = integer(fields[8], 180);
      double[] normalized = {lat, lon, index, kind, speed, distance, direction, directionType, angle};
      System.arraycopy(normalized, 0, values, offset, normalized.length);
      identities[row] = identity;
    }
    catch (NumberFormatException e)
    {
      throw invalid();
    }
  }

  private double integer(String field, long max) throws IOException
  {
    double value = Double.parseDouble(field.trim());
    if (!Double.isFinite(value) || value < 0 || value > max || Math.floor(value) != value)
      throw invalid();
    return value;
  }

  private int kind(int type, String label) throws IOException
  {
    return switch (type)
    {
      case 1 ->
        switch (label)
        {
        case "Статическая камера" -> RoadEventKind.CAMERA;
        case "Муляж" -> RoadEventKind.DUMMY;
        case "Видеоконтроль" -> RoadEventKind.VIDEO;
        default -> throw invalid();
        };
      case 3 -> RoadEventKind.RED_LIGHT;
      case 4 ->
        switch (label)
        {
        case "Камера средней скорости 1" -> RoadEventKind.AVERAGE_START;
        case "Камера средней скорости 2" -> RoadEventKind.AVERAGE_END;
        default -> throw invalid();
        };
      case 5 -> RoadEventKind.MOBILE;
      case 11 -> RoadEventKind.LANE_CONTROL;
      case 20 -> RoadEventKind.POLICE;
      case 21 -> RoadEventKind.RAILWAY;
      case 22 ->
        switch (label)
        {
        case "Пешеходный переход" -> RoadEventKind.CROSSING;
        case "Осторожно дети" -> RoadEventKind.CHILDREN;
        default -> throw invalid();
        };
      case 100 ->
        switch (label)
        {
        case "Начало населенного пункта" -> RoadEventKind.SETTLEMENT_START;
        case "Конец населенного пункта" -> RoadEventKind.SETTLEMENT_END;
        default -> throw invalid();
        };
      case 101 -> RoadEventKind.SPEED_LIMIT;
      case 102 -> RoadEventKind.BUMP;
      case 103 -> RoadEventKind.BAD_ROAD;
      case 104 -> RoadEventKind.BEND;
      case 105 -> RoadEventKind.INTERSECTION;
      case 106 -> RoadEventKind.DANGER;
      case 107 -> RoadEventKind.NO_OVERTAKING;
      default -> throw invalid();
    };
  }

  private IOException invalid()
  {
    return new IOException("Invalid road event export at line " + mLine);
  }
  @Override
  public void close() throws IOException
  {
    if (mReader != null)
      mReader.close();
  }
}
