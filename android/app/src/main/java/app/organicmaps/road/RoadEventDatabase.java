package app.organicmaps.road;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import app.organicmaps.sdk.road.RoadEventBatch;
import app.organicmaps.sdk.road.RoadEvents;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Country/source replacement is atomic. Rendering never queries SQLite or blocks on its writer. */
final class RoadEventDatabase extends SQLiteOpenHelper
{
  private static final String[] COLUMNS = {"lat",      "lon",       "source_index",   "kind", "speed",
                                           "distance", "direction", "direction_type", "angle"};
  RoadEventDatabase(Context context)
  {
    this(context, "road_events.sqlite");
  }

  RoadEventDatabase(Context context, String name)
  {
    super(context, new File(context.getNoBackupFilesDir(), name).getPath(), null, 1);
  }

  @Override
  public void onCreate(SQLiteDatabase db)
  {
    db.execSQL(
        "CREATE TABLE events (id INTEGER PRIMARY KEY, provider TEXT NOT NULL, country TEXT NOT NULL, "
        + "source_id TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, source_index INTEGER NOT NULL, "
        + "kind INTEGER NOT NULL, speed INTEGER NOT NULL, distance INTEGER NOT NULL, direction INTEGER NOT NULL, "
        + "direction_type INTEGER NOT NULL, angle INTEGER NOT NULL)");
    db.execSQL("CREATE INDEX events_country_provider ON events(country, provider)");
    db.execSQL("CREATE TABLE imports (provider TEXT NOT NULL, country TEXT NOT NULL, imported_at INTEGER NOT NULL, "
               + "count INTEGER NOT NULL, PRIMARY KEY(provider, country))");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion)
  {
    throw new IllegalStateException("Unsupported road database upgrade");
  }

  int importFile(RoadDataProvider provider, String country, InputStream input) throws IOException
  {
    if (!country.matches("[A-Z]{2}"))
      throw new IllegalArgumentException("Invalid country code");
    try (RoadEventImporter importer = provider.newImporter())
    {
      importer.open(input);
      int count = 0;
      SQLiteDatabase db = getWritableDatabase();
      db.beginTransaction();
      try (SQLiteStatement insert =
               db.compileStatement("INSERT INTO events(provider,country,source_id," + String.join(",", COLUMNS)
                                   + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"))
      {
        db.delete("events", "provider=? AND country=?", new String[] {provider.id(), country});
        RoadEventBatch batch;
        while ((batch = importer.nextBatch()) != null)
        {
          if (batch.identities.length == 0)
            throw new IOException("Empty importer batch");
          for (int i = 0; i < batch.identities.length; ++i)
          {
            insert.bindString(1, provider.id());
            insert.bindString(2, country);
            insert.bindString(3, batch.identities[i]);
            for (int column = 0; column < COLUMNS.length; ++column)
              insert.bindDouble(column + 4, batch.values[i * RoadEventBatch.WIDTH + column]);
            insert.executeInsert();
          }
          count += batch.identities.length;
        }
        ContentValues metadata = new ContentValues();
        metadata.put("provider", provider.id());
        metadata.put("country", country);
        metadata.put("imported_at", System.currentTimeMillis());
        metadata.put("count", count);
        db.insertWithOnConflict("imports", null, metadata, SQLiteDatabase.CONFLICT_REPLACE);
        db.setTransactionSuccessful();
      }
      finally
      {
        db.endTransaction();
      }
      return count;
    }
  }

  List<RoadDataManager.ImportedCountry> imports(String provider)
  {
    List<RoadDataManager.ImportedCountry> result = new ArrayList<>();
    try (Cursor cursor = getReadableDatabase().query("imports", new String[] {"country", "imported_at", "count"},
                                                     "provider=?", new String[] {provider}, null, null, "country"))
    {
      while (cursor.moveToNext())
        result.add(new RoadDataManager.ImportedCountry(cursor.getString(0), cursor.getLong(1), cursor.getInt(2)));
    }
    return List.copyOf(result);
  }

  boolean hasCountry(String provider, String country)
  {
    try (Cursor cursor = getReadableDatabase().rawQuery("SELECT 1 FROM imports WHERE provider=? AND country=?",
                                                        new String[] {provider, country}))
    {
      return cursor.moveToFirst();
    }
  }

  void loadIndex(Set<String> countries)
  {
    RoadEvents.nativeBeginIndex();
    try
    {
      for (String country : countries)
      {
        try (Cursor sources = getReadableDatabase().query("imports", new String[] {"provider", "imported_at"},
                                                          "country=?", new String[] {country}, null, null, null))
        {
          while (sources.moveToNext())
            appendIndex(sources.getString(0), country, sources.getLong(1));
        }
      }
      RoadEvents.nativePublishIndex();
    }
    finally
    {
      RoadEvents.nativeDiscardPrepared();
    }
  }

  private void appendIndex(String provider, String country, long importedAt)
  {
    List<String> fields = new ArrayList<>(Arrays.asList(COLUMNS));
    fields.add("source_id");
    String prefix = provider + ":" + country + ":";
    try (Cursor cursor =
             getReadableDatabase().query("events", fields.toArray(new String[0]), "country=? AND provider=?",
                                         new String[] {country, provider}, null, null, "id"))
    {
      double[] values = new double[512 * RoadEventBatch.WIDTH];
      String[] ids = new String[512];
      int count = 0;
      while (cursor.moveToNext())
      {
        for (int column = 0; column < COLUMNS.length; ++column)
          values[count * RoadEventBatch.WIDTH + column] = cursor.getDouble(column);
        ids[count] = prefix + cursor.getString(9);
        if (++count == ids.length)
        {
          RoadEvents.nativeAppendIndex(values, ids, importedAt);
          count = 0;
        }
      }
      if (count > 0)
        RoadEvents.nativeAppendIndex(Arrays.copyOf(values, count * RoadEventBatch.WIDTH), Arrays.copyOf(ids, count),
                                     importedAt);
    }
  }
}
