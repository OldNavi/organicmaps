package app.organicmaps.road;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.os.Parcel;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.road.RoadEventBatch;
import app.organicmaps.sdk.road.RoadEventKind;
import app.organicmaps.sdk.road.RoadEvents;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;

public class RoadEventDatabaseTest
{
  private static final String HEADER = "IDX,X,Y,TYPE,SPEED,DIRTYPE,DIRECTION,DISTANCE,ANGLE\n";
  private static final String CAMERA = "1,38.812283,55.122158,1,90,1,289,500,20 // source | Статическая камера\n";
  private static InputStream text(String value)
  {
    return new ByteArrayInputStream(value.getBytes(Charset.forName("windows-1251")));
  }

  private static Context initialize() throws Exception
  {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    Context context = instrumentation.getTargetContext();
    CountDownLatch ready = new CountDownLatch(1);
    instrumentation.runOnMainSync(() -> {
      try
      {
        if (!MwmApplication.from(context).initOrganicMaps(ready::countDown))
          ready.countDown();
      }
      catch (Exception e)
      {
        throw new AssertionError(e);
      }
    });
    assertTrue(ready.await(30, TimeUnit.SECONDS));
    return context;
  }

  @Test
  public void replacementIsAtomicAndKeepsOtherCountriesAndProviders() throws Exception
  {
    Context context = initialize();
    String name = "road-events-test.sqlite";
    File file = new File(context.getNoBackupFilesDir(), name);
    SQLiteDatabase.deleteDatabase(file);
    RoadDataProvider source = new OpenSpeedCamProvider();
    try (RoadEventDatabase db = new RoadEventDatabase(context, name))
    {
      assertEquals(1, db.importFile(source, "RU", text(HEADER + CAMERA)));
      assertEquals(1, db.importFile(source, "BY", text(HEADER + CAMERA)));
      assertEquals(2, db.importFile(source, "RU", text(HEADER + CAMERA + CAMERA)));
      assertEquals(3, DatabaseUtils.queryNumEntries(db.getReadableDatabase(), "events"));
      assertTrue(db.hasCountry(source.id(), "RU"));
      try
      {
        db.importFile(source, "RU", text("<html>login</html>"));
        fail("Expected import failure");
      }
      catch (IOException expected)
      {}
      assertEquals(3, DatabaseUtils.queryNumEntries(db.getReadableDatabase(), "events"));
      // A second provider supplies its own parser, without the text export format or native importer.
      RoadDataProvider other = new TestProvider(false);
      assertEquals(1, db.importFile(other, "RU", text("ignored")));
      assertEquals(4, DatabaseUtils.queryNumEntries(db.getReadableDatabase(), "events"));
      try
      {
        db.importFile(new TestProvider(true), "RU", text("ignored"));
        fail("Expected partial batch failure");
      }
      catch (IOException expected)
      {}
      assertEquals(4, DatabaseUtils.queryNumEntries(db.getReadableDatabase(), "events"));
      assertEquals(0, db.importFile(source, "RU", text(HEADER)));
      assertEquals(2, DatabaseUtils.queryNumEntries(db.getReadableDatabase(), "events"));
      assertTrue("A valid empty export is downloaded data", db.hasCountry(source.id(), "RU"));
    }
    finally
    {
      SQLiteDatabase.deleteDatabase(file);
    }
  }

  @Test
  public void requestExportsExactlyOneCountryAndComputesConfirmationDate() throws Exception
  {
    var payload = OpenSpeedCamProvider.exportRequest("RU", LocalDate.of(2028, 2, 29));
    assertEquals(1, payload.getJSONArray("country").length());
    assertEquals("RU", payload.getJSONArray("country").getString(0));
    assertEquals("2027-02-28", payload.getString("confirmed"));
    assertTrue(payload.getBoolean("withIdPoint"));
    assertFalse(payload.getBoolean("saveToUserExport"));
    try
    {
      OpenSpeedCamProvider.exportRequest("RU,BY", LocalDate.now());
      fail();
    }
    catch (IllegalArgumentException expected)
    {}
  }

  @Test
  public void countryLookupUsesMapRegionsAndStableIsoCodes() throws Exception
  {
    Context context = initialize();
    JSONObject codes = RoadDataManager.loadCountryCodes(context);
    double[][] points = {{55.117587, 38.814197}, {51.5072, -0.1276}, {-36.8485, 174.7633}};
    String[] expected = {"RU", "GB", "NZ"};
    for (int i = 0; i < points.length; ++i)
    {
      String[] regions = RoadEvents.nativeCountriesNear(points[i][0], points[i][1]);
      assertTrue(regions.length > 0);
      assertEquals(expected[i], codes.getString(regions[0]));
    }
  }

  @Test
  public void credentialsSurviveRecreationAndStayIsolatedByProvider() throws Exception
  {
    Context context = initialize();
    String first = "credential-test-first";
    String second = "credential-test-second";
    var credentials = new RoadDataCredentials(context);
    try
    {
      credentials.save(first, new RoadDataCredentials.Account("test-login", "test-only-password"));
      credentials.save(second, new RoadDataCredentials.Account("another-login", "another-test-password"));
      var reopened = new RoadDataCredentials(context);
      assertEquals("test-login", reopened.load(first).login());
      assertEquals("test-only-password", reopened.load(first).password());
      assertEquals("another-login", reopened.load(second).login());
      reopened.clear(first);
      assertNull(reopened.load(first));
      assertNotNull(reopened.load(second));
    }
    finally
    {
      credentials.clear(first);
      credentials.clear(second);
    }
  }

  @Test
  public void selectedEventSurvivesPlacePageParcelRoundTrip()
  {
    MapObject original = MapObject.createMapObject(MapObject.POI, "Camera", "", 55, 38);
    original.setRoadEvent("example.org:RU:shared-id", RoadEventKind.CAMERA, 90, 1790098067000L);
    Parcel parcel = Parcel.obtain();
    try
    {
      original.writeToParcel(parcel, 0);
      parcel.setDataPosition(0);
      MapObject restored = MapObject.CREATOR.createFromParcel(parcel);
      assertTrue(original.sameAs(restored));
      assertEquals(original.getRoadEvent(), restored.getRoadEvent());
      assertEquals(90, restored.getRoadEvent().speedKmh());
      restored.setRoadEvent("example.org:RU:shared-id", RoadEventKind.DUMMY, 90, 1790098067000L);
      assertFalse("Coincident camera and dummy are different selections", original.sameAs(restored));
      restored.setRoadEvent("example.org:RU:shared-id", RoadEventKind.CAMERA, 90, 1790098067001L);
      assertFalse("Updated metadata must refresh an already open card", original.sameAs(restored));
    }
    finally
    {
      parcel.recycle();
    }
  }

  private static final class TestProvider implements RoadDataProvider
  {
    private final boolean mFail;
    TestProvider(boolean fail)
    {
      mFail = fail;
    }
    @Override
    public String id()
    {
      return "test-source";
    }
    @Override
    public void login(String login, String password)
    {}
    @Override
    public void logout()
    {}
    @Override
    public List<String> countries()
    {
      return List.of("RU");
    }
    @Override
    public void download(String country, File file)
    {}
    @Override
    public RoadEventImporter newImporter()
    {
      return new RoadEventImporter() {
        private int mRead;
        @Override
        public void open(InputStream input)
        {}
        @Override
        public RoadEventBatch nextBatch() throws IOException
        {
          if (mRead++ > 0 && !mFail)
            return null;
          int offset = mRead - 1;
          if (offset > 0)
            throw new IOException("Synthetic interrupted parser");
          return new RoadEventBatch(new double[] {55, 38, 1, 0, 60, 500, 180, 1, 20}, new String[] {"source"});
        }
        @Override
        public void close()
        {}
      };
    }
  }
}
