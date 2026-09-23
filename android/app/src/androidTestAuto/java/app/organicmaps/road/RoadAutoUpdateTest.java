package app.organicmaps.road;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.road.RoadEventBatch;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class RoadAutoUpdateTest
{
  private static void main(Runnable action)
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
  }

  private static final class Source implements RoadDataProvider
  {
    final String id = "update-test-" + UUID.randomUUID();
    int downloads;
    String record = "old";
    boolean failDownload, failImport, failAuth;
    Runnable afterDownload, afterBatch;
    public String id()
    {
      return id;
    }
    public void login(String login, String password) throws IOException
    {
      if (failAuth)
        throw new AuthenticationException();
    }
    public void logout() {}
    public List<String> countries()
    {
      return List.of("RU", "BY");
    }
    public void download(String country, File file) throws IOException
    {
      downloads++;
      if (failDownload)
        throw new IOException("Synthetic network failure");
      try (var out = new FileOutputStream(file))
      {
        out.write(1);
      }
      if (afterDownload != null)
        afterDownload.run();
    }
    public RoadEventImporter newImporter()
    {
      return new RoadEventImporter() {
        boolean emitted;
        public void open(InputStream stream) {}
        public RoadEventBatch nextBatch() throws IOException
        {
          if (emitted)
          {
            if (afterBatch != null)
              afterBatch.run();
            if (failImport)
              throw new IOException("Synthetic truncated export");
            return null;
          }
          emitted = true;
          return new RoadEventBatch(new double[] {55, 38, 1, 0, 60, 500, 180, 1, 20}, new String[] {record});
        }
        public void close() {}
      };
    }
  }

  private static final class Fixture implements AutoCloseable
  {
    final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    final SharedPreferences prefs = MwmApplication.prefs(context);
    final Source source = new Source();
    final File file = new File(context.getNoBackupFilesDir(), source.id() + ".sqlite");
    final RoadEventDatabase database = new RoadEventDatabase(context, file.getName());
    final AtomicBoolean stopped = new AtomicBoolean();
    final RoadDataManager manager;
    final String oldCountry = prefs.getString(RoadUpdateSettings.CURRENT_COUNTRY, "");
    final long oldSeen = prefs.getLong(RoadUpdateSettings.COUNTRY_OBSERVED_AT, 0);
    final long oldImport = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10);

    Fixture() throws Exception
    {
      database.importFile(source, "RU", new ByteArrayInputStream(new byte[0]));
      database.importFile(source, "BY", new ByteArrayInputStream(new byte[0]));
      database.getWritableDatabase().execSQL("UPDATE imports SET imported_at=?", new Object[] {oldImport});
      new RoadDataCredentials(context).save(source.id(), new RoadDataCredentials.Account("test", "test-only"));
      AtomicReference<RoadDataManager> created = new AtomicReference<>();
      main(() -> {
        prefs.edit()
            .putBoolean("road_events_provider_configured." + source.id(), true)
            .putBoolean(RoadUpdateSettings.enabledKey(source.id()), true)
            .putString(RoadUpdateSettings.CURRENT_COUNTRY, "RU")
            .putLong(RoadUpdateSettings.COUNTRY_OBSERVED_AT, System.currentTimeMillis())
            .apply();
        created.set(new RoadDataManager(context, source, database));
      });
      manager = created.get();
      source.record = "new";
    }

    RoadDataManager.UpdateResult run() throws Exception
    {
      var future = new CompletableFuture<RoadDataManager.UpdateResult>();
      main(() -> manager.updateAutomatically(source.id(), "RU", stopped::get, future));
      return future.get(10, TimeUnit.SECONDS);
    }

    String record(String country)
    {
      try (var cursor = database.getReadableDatabase().rawQuery("SELECT source_id FROM events WHERE country=?",
                                                                new String[] {country}))
      {
        assertTrue(cursor.moveToFirst());
        return cursor.getString(0);
      }
    }

    public void close() throws Exception
    {
      // These are isolated managers, not the application singleton. Release their test executors/listener.
      for (String name : new String[] {"mNetwork", "mWorker"})
      {
        var field = RoadDataManager.class.getDeclaredField(name);
        field.setAccessible(true);
        ((ExecutorService) field.get(manager)).shutdownNow();
      }
      var listener = RoadDataManager.class.getDeclaredField("mWarningSettingsListener");
      listener.setAccessible(true);
      prefs.unregisterOnSharedPreferenceChangeListener(
          (SharedPreferences.OnSharedPreferenceChangeListener) listener.get(manager));
      main(()
               -> prefs.edit()
                      .putString(RoadUpdateSettings.CURRENT_COUNTRY, oldCountry)
                      .putLong(RoadUpdateSettings.COUNTRY_OBSERVED_AT, oldSeen)
                      .remove("road_events_provider_configured." + source.id())
                      .remove(RoadUpdateSettings.enabledKey(source.id()))
                      .remove(RoadUpdateSettings.intervalKey(source.id()))
                      .remove(RoadUpdateSettings.authKey(source.id()))
                      .apply());
      new RoadDataCredentials(context).clear(source.id());
      database.close();
      SQLiteDatabase.deleteDatabase(file);
    }
  }

  @Test
  public void staleCurrentCountryUpdatesOnceWithoutNativeInitialization() throws Exception
  {
    try (var f = new Fixture())
    {
      assertEquals(RoadDataManager.UpdateResult.UPDATED, f.run());
      assertEquals("new", f.record("RU"));
      assertEquals("old", f.record("BY"));
      assertTrue(f.database.importedAt(f.source.id(), "RU") > f.oldImport);
      assertEquals(RoadDataManager.UpdateResult.SKIPPED, f.run());
      assertEquals(1, f.source.downloads);
    }
  }

  @Test
  public void disabledStaleLocationAndRaisedIntervalDoNotExport() throws Exception
  {
    try (var f = new Fixture())
    {
      f.prefs.edit().putBoolean(RoadUpdateSettings.enabledKey(f.source.id()), false).apply();
      assertEquals(RoadDataManager.UpdateResult.SKIPPED, f.run());
      f.prefs.edit()
          .putBoolean(RoadUpdateSettings.enabledKey(f.source.id()), true)
          .putLong(RoadUpdateSettings.COUNTRY_OBSERVED_AT, 1)
          .apply();
      assertEquals(RoadDataManager.UpdateResult.SKIPPED, f.run());
      f.prefs.edit()
          .putLong(RoadUpdateSettings.COUNTRY_OBSERVED_AT, System.currentTimeMillis())
          .putInt(RoadUpdateSettings.intervalKey(f.source.id()), 30)
          .apply();
      assertEquals(RoadDataManager.UpdateResult.SKIPPED, f.run());
      assertEquals(0, f.source.downloads);
    }
  }

  @Test
  public void changedCountryAndCancelledImportKeepPreviousData() throws Exception
  {
    try (var f = new Fixture())
    {
      f.source.afterDownload = () -> f.prefs.edit().putString(RoadUpdateSettings.CURRENT_COUNTRY, "BY").apply();
      assertEquals(RoadDataManager.UpdateResult.SKIPPED, f.run());
      assertEquals("old", f.record("RU"));
      f.source.afterDownload = null;
      f.prefs.edit().putString(RoadUpdateSettings.CURRENT_COUNTRY, "RU").apply();
      f.source.afterBatch = () -> f.stopped.set(true);
      assertEquals(RoadDataManager.UpdateResult.SKIPPED, f.run());
      assertEquals("old", f.record("RU"));
      assertEquals(f.oldImport, f.database.importedAt(f.source.id(), "RU"));
    }
  }

  @Test
  public void failedDownloadOrTruncatedImportCanRetryWithoutReplacingData() throws Exception
  {
    try (var f = new Fixture())
    {
      f.source.failDownload = true;
      assertEquals(RoadDataManager.UpdateResult.RETRY, f.run());
      f.source.failDownload = false;
      f.source.failImport = true;
      assertEquals(RoadDataManager.UpdateResult.RETRY, f.run());
      assertEquals("old", f.record("RU"));
      assertEquals(f.oldImport, f.database.importedAt(f.source.id(), "RU"));
    }
  }

  @Test
  public void concurrentAutomaticRequestDoesNotStartAnotherExport() throws Exception
  {
    try (var f = new Fixture())
    {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      f.source.afterDownload = () ->
      {
        entered.countDown();
        try
        {
          release.await(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
          Thread.currentThread().interrupt();
        }
      };
      var first = new CompletableFuture<RoadDataManager.UpdateResult>();
      main(() -> f.manager.updateAutomatically(f.source.id(), "RU", f.stopped::get, first));
      try
      {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(RoadDataManager.UpdateResult.RETRY, f.run());
        assertEquals(1, f.source.downloads);
      }
      finally
      {
        release.countDown();
      }
      assertEquals(RoadDataManager.UpdateResult.UPDATED, first.get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  public void rejectedCredentialsPauseFutureAutomaticAttempts() throws Exception
  {
    try (var f = new Fixture())
    {
      f.source.failAuth = true;
      assertEquals(RoadDataManager.UpdateResult.NEEDS_LOGIN, f.run());
      assertTrue(RoadUpdateSettings.needsLogin(f.prefs, f.source.id()));
      assertEquals(RoadDataManager.UpdateResult.SKIPPED, f.run());
      assertEquals(0, f.source.downloads);
      assertEquals("old", f.record("RU"));
    }
  }
}
