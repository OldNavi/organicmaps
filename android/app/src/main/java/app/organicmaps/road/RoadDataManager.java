package app.organicmaps.road;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteException;
import android.location.Location;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import app.organicmaps.BuildConfig;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.cluster.NavigationProvider;
import app.organicmaps.sdk.road.RoadEvents;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.settings.SpeedWarningSettings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import org.json.JSONException;
import org.json.JSONObject;

/** All database/index operations use one worker. Network waits never block position processing. */
public final class RoadDataManager
{
  public static final String SETTINGS = "road_events_settings";
  public static final String ENABLED = "road_events_enabled";
  public static final String WARNINGS = "road_events_warnings";
  public static final String CATEGORIES = "road_events_categories";
  public static final String COUNTRY = "road_events_country";
  private static final String CONFIGURED = "road_events_provider_configured";
  private static final String LAST_COUNTRIES = "road_events_last_countries";
  private static RoadDataManager sInstance;
  private final Context mContext;
  private final SharedPreferences mPrefs;
  private final RoadEventDatabase mDatabase;
  private final RoadDataCredentials mCredentials;
  private final int[] mMinZooms;
  private final SharedPreferences.OnSharedPreferenceChangeListener mWarningSettingsListener;
  private boolean mAuthenticated;
  private String mAccountName = "";
  private final RoadDataProvider mProvider;
  private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
  private final ExecutorService mNetwork = Executors.newSingleThreadExecutor();
  private final Handler mMain = new Handler(Looper.getMainLooper());
  private final MutableLiveData<State> mState =
      new MutableLiveData<>(new State(false, 0, "", false, List.of(), List.of()));
  private Set<String> mLoadedCountries = Set.of();
  private JSONObject mCountryCodes;
  private Location mLastCountryLocation;
  private volatile boolean mEnabled;
  private boolean mWarningsEnabled;
  private volatile String mCurrentCountry = "";
  private boolean mBusy;
  private int mMessage;
  private boolean mMissing;
  private List<String> mCountries = List.of();
  private List<ImportedCountry> mImports = List.of();
  private volatile boolean mInitialized;
  private AutomaticUpdate mAutomaticUpdate;
  private long mLastUpdateCheck;
  private String mLastUpdateCountry = "";

  public record ImportedCountry(String country, long updatedAt, int count)
  {
  }

  public record State(boolean busy, int message, String currentCountry, boolean missing, List<String> countries,
                      List<ImportedCountry> imports)
  {
  }

  private RoadDataManager(Context context)
  {
    this(context, new OpenSpeedCamProvider(), new RoadEventDatabase(context));
  }

  RoadDataManager(Context context, RoadDataProvider provider, RoadEventDatabase database)
  {
    mContext = context.getApplicationContext();
    mPrefs = MwmApplication.prefs(mContext);
    mDatabase = database;
    mProvider = provider;
    mCredentials = new RoadDataCredentials(mContext);
    mMinZooms = RoadEventDisplayConfig.load(mContext);
    RoadEventVisibility.migrate(mPrefs);
    mWarningSettingsListener = (prefs, key) ->
    {
      if (SpeedWarningSettings.LEVEL_KEY.equals(key))
        configure();
    };
    mPrefs.registerOnSharedPreferenceChangeListener(mWarningSettingsListener);
  }

  public static boolean available()
  {
    return "auto".equals(BuildConfig.FLAVOR);
  }
  public static RoadDataManager get(Context context)
  {
    if (!available())
      throw new IllegalStateException("Road data is automotive-only");
    if (sInstance == null)
      sInstance = new RoadDataManager(context);
    return sInstance;
  }
  public String accountName()
  {
    return mAccountName;
  }

  public String providerId()
  {
    return mProvider.id();
  }

  public LiveData<State> state()
  {
    return mState;
  }
  public boolean enabled()
  {
    return mPrefs.getBoolean(ENABLED, false);
  }
  public boolean warnings()
  {
    return enabled() && SpeedWarningSettings.level(mContext) != SpeedWarningSettings.OFF;
  }
  public int visibleKinds()
  {
    return RoadEventVisibility.get(mPrefs, RoutingController.get().isNavigating());
  }
  public boolean configured()
  {
    return mPrefs.getBoolean(CONFIGURED + "." + mProvider.id(), false);
  }
  public String selectedCountry()
  {
    return mPrefs.getString(COUNTRY, mCurrentCountry);
  }
  public void selectCountry(String country)
  {
    mPrefs.edit().putString(COUNTRY, country).apply();
  }

  public void initialize()
  {
    if (mInitialized)
      return;
    mInitialized = true;
    configure();
    updateSchedule();
    mWorker.execute(this::refreshImports);
    readCredentials((login, password) -> {
      mAccountName = login;
      emit();
    });
  }

  private void loadFallbackIndex()
  {
    if (!mEnabled || !mLoadedCountries.isEmpty())
      return;
    Set<String> countries = new LinkedHashSet<>(mPrefs.getStringSet(LAST_COUNTRIES, Set.of()));
    // The visual layer can use the last country before GNSS resumes. Alerts still require fresh fixes.
    if (countries.isEmpty())
    {
      var imports = mDatabase.imports(mProvider.id());
      if (imports.size() == 1)
        countries.add(imports.get(0).country());
    }
    if (!countries.isEmpty())
    {
      mDatabase.loadIndex(countries);
      mLoadedCountries = countries;
      mPrefs.edit().putStringSet(LAST_COUNTRIES, countries).apply();
      mMain.post(this::refreshLayer);
    }
  }

  public void configure()
  {
    // A WorkManager job can import SQLite before any activity or native map has been initialized.
    if (!mInitialized)
      return;
    boolean wasEnabled = mEnabled;
    boolean warningsChanged = mWarningsEnabled != warnings();
    mEnabled = enabled();
    mWarningsEnabled = warnings();
    RoadEvents.nativeConfigure(mEnabled, mWarningsEnabled, visibleKinds(), mMinZooms);
    if (wasEnabled != mEnabled || warningsChanged)
      NavigationProvider.invalidateRoadData();
    if (!mEnabled && wasEnabled)
    {
      // Hiding the layer must not discard its index: re-enabling can redraw immediately without database I/O.
      mMissing = false;
      emit();
    }
    else if (mEnabled && !wasEnabled)
    {
      mLastCountryLocation = null;
      Location location = MwmApplication.from(mContext).getLocationHelper().getSavedLocation();
      if (location != null)
        onLocation(location);
      mWorker.execute(this::loadFallbackIndex);
    }
  }

  public void onLocation(Location location)
  {
    if (!mEnabled && !RoadUpdateSettings.enabled(mPrefs, providerId()))
      return;
    long age = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
    if (age < 0 || age > 30_000_000_000L || !location.hasAccuracy() || location.getAccuracy() > 1000)
      return;
    if (mLastCountryLocation != null && location.distanceTo(mLastCountryLocation) < 1000
        && location.getElapsedRealtimeNanos() - mLastCountryLocation.getElapsedRealtimeNanos() < 30_000_000_000L)
      return;
    long observedAt = System.currentTimeMillis() - TimeUnit.NANOSECONDS.toMillis(age);
    mLastCountryLocation = new Location(location);
    double lat = location.getLatitude();
    double lon = location.getLongitude();
    mWorker.execute(() -> {
      try
      {
        if (!mEnabled && !RoadUpdateSettings.enabled(mPrefs, providerId()))
          return;
        CountrySelection selection = countriesNear(lat, lon);
        Set<String> countries = selection.nearby();
        String current = selection.current();
        if (mEnabled && !countries.equals(mLoadedCountries))
        {
          mDatabase.loadIndex(countries);
          mLoadedCountries = countries;
          mPrefs.edit().putStringSet(LAST_COUNTRIES, countries).apply();
          mMain.post(this::refreshLayer);
        }
        boolean missing = !current.isEmpty() && !mDatabase.hasCountry(mProvider.id(), current);
        mMain.post(() -> {
          mCurrentCountry = current;
          rememberCurrentCountry(current, observedAt);
          mMissing = mEnabled && missing;
          emit();
        });
      }
      catch (IOException | JSONException | SQLiteException e)
      {
        mMain.post(() -> finish(R.string.road_events_failed));
      }
    });
  }

  private record CountrySelection(String current, Set<String> nearby)
  {
  }

  private CountrySelection countriesNear(double lat, double lon) throws IOException, JSONException
  {
    if (mCountryCodes == null)
      mCountryCodes = loadCountryCodes(mContext);
    Set<String> result = new LinkedHashSet<>();
    String[] regions = RoadEvents.nativeCountriesNear(lat, lon);
    for (String name : regions)
    {
      String code = mCountryCodes.optString(name);
      if (!code.isEmpty())
        result.add(code);
    }
    return new CountrySelection(regions.length == 0 ? "" : mCountryCodes.optString(regions[0]), result);
  }

  static JSONObject loadCountryCodes(Context context) throws IOException, JSONException
  {
    try (InputStream in = context.getAssets().open("road_event_countries.json");
         ByteArrayOutputStream bytes = new ByteArrayOutputStream())
    {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = in.read(buffer)) != -1)
        bytes.write(buffer, 0, count);
      return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }
  }

  public void login(String login, String password)
  {
    if (!begin())
      return;
    mNetwork.execute(() -> {
      try
      {
        mAuthenticated = false;
        mProvider.login(login, password);
        mCredentials.save(mProvider.id(), new RoadDataCredentials.Account(login, password));
        mAuthenticated = true;
        List<String> countries = mProvider.countries();
        mMain.post(() -> {
          mAccountName = login;
          mCountries = List.copyOf(countries);
          mPrefs.edit().putBoolean(CONFIGURED + "." + mProvider.id(), true).apply();
          mPrefs.edit().putBoolean(RoadUpdateSettings.authKey(providerId()), false).apply();
          updateSchedule();
          finish(R.string.road_events_signed_in);
        });
      }
      catch (IOException | SQLiteException e)
      {
        mMain.post(() -> finish(R.string.road_events_login_failed));
      }
    });
  }

  public void readCredentials(BiConsumer<String, String> callback)
  {
    mNetwork.execute(() -> {
      try
      {
        var account = mCredentials.load(mProvider.id());
        mMain.post(
            () -> callback.accept(account == null ? "" : account.login(), account == null ? "" : account.password()));
      }
      catch (IOException e)
      {
        mMain.post(() -> callback.accept("", ""));
      }
    });
  }

  public void logout()
  {
    if (!begin())
      return;
    mNetwork.execute(() -> {
      mProvider.logout();
      mAuthenticated = false;
      mCredentials.clear(mProvider.id());
      mMain.post(() -> {
        mAccountName = "";
        mCountries = List.of();
        mPrefs.edit().putBoolean(CONFIGURED + "." + mProvider.id(), false).apply();
        updateSchedule();
        finish(R.string.not_signed_in);
      });
    });
  }

  private void ensureAuthenticated(boolean loadCountries) throws IOException
  {
    if (mAuthenticated)
      return;
    var account = mCredentials.load(mProvider.id());
    if (account == null)
      throw new RoadDataProvider.AuthenticationException();
    mProvider.login(account.login(), account.password());
    mAuthenticated = true;
    if (!loadCountries)
      return;
    List<String> countries = mProvider.countries();
    mMain.post(() -> {
      mCountries = List.copyOf(countries);
      emit();
    });
  }

  private void downloadExport(String country, File file, boolean loadCountries, BooleanSupplier allowed)
      throws IOException
  {
    if (!allowed.getAsBoolean())
      throw new IOException("Update no longer current");
    ensureAuthenticated(loadCountries);
    if (!allowed.getAsBoolean())
      throw new IOException("Update no longer current");
    try
    {
      mProvider.download(country, file);
    }
    catch (RoadDataProvider.SessionExpiredException expired)
    {
      mAuthenticated = false;
      if (!allowed.getAsBoolean())
        throw new IOException("Update no longer current");
      ensureAuthenticated(loadCountries);
      if (!allowed.getAsBoolean())
        throw new IOException("Update no longer current");
      mProvider.download(country, file);
    }
  }

  public void download(String country)
  {
    if (!country.matches("[A-Z]{2}") || !begin())
      return;
    mNetwork.execute(() -> {
      File file = null;
      try
      {
        file = File.createTempFile("road-export-", ".txt", mContext.getCacheDir());
        downloadExport(country, file, true, () -> true);
        File downloaded = file;
        mWorker.execute(() -> {
          try (InputStream in = new FileInputStream(downloaded))
          {
            importStream(country, in);
          }
          catch (IOException | SQLiteException e)
          {
            mMain.post(() -> finish(R.string.road_events_failed));
          }
          finally
          {
            downloaded.delete();
          }
        });
      }
      catch (IOException | SQLiteException e)
      {
        if (file != null)
          file.delete();
        mMain.post(() -> finish(R.string.road_events_download_failed));
      }
    });
  }

  public void importFile(String country, Uri uri)
  {
    if (!country.matches("[A-Z]{2}") || !begin())
      return;
    mWorker.execute(() -> {
      try (InputStream in = mContext.getContentResolver().openInputStream(uri))
      {
        if (in == null)
          throw new IOException("Cannot open import");
        importStream(country, in);
      }
      catch (IOException | SQLiteException e)
      {
        mMain.post(() -> finish(R.string.road_events_failed));
      }
    });
  }

  private void importStream(String country, InputStream input) throws IOException
  {
    mDatabase.importFile(mProvider, country, input);
    publishImport(country, () -> finish(R.string.road_events_imported));
  }

  private void publishImport(String country, Runnable onComplete)
  {
    refreshImports();
    if (mInitialized && mEnabled && mLoadedCountries.isEmpty())
      mLoadedCountries = Set.of(country);
    if (mInitialized && mLoadedCountries.contains(country))
      mDatabase.loadIndex(mLoadedCountries);
    mMain.post(() -> {
      if (country.equals(mCurrentCountry))
        mMissing = false;
      refreshLayer();
      onComplete.run();
    });
  }

  private void refreshImports()
  {
    List<ImportedCountry> imports = mDatabase.imports(mProvider.id());
    mMain.post(() -> {
      mImports = imports;
      emit();
    });
  }

  private void refreshLayer()
  {
    if (!mInitialized)
      return;
    RoadEvents.nativeConfigure(enabled(), warnings(), visibleKinds(), mMinZooms);
    NavigationProvider.invalidateRoadData();
  }

  public void updateSchedule()
  {
    RoadUpdateScheduler.sync(mContext, providerId(), configured());
    mLastCountryLocation = null;
    if (mInitialized)
    {
      Location location = MwmApplication.from(mContext).getLocationHelper().getSavedLocation();
      if (location != null)
        onLocation(location);
    }
    emit();
  }

  private void rememberCurrentCountry(String country, long observedAt)
  {
    mPrefs.edit()
        .putString(RoadUpdateSettings.CURRENT_COUNTRY, country)
        .putLong(RoadUpdateSettings.COUNTRY_OBSERVED_AT, observedAt)
        .apply();
    long now = SystemClock.elapsedRealtime();
    if (configured() && (!country.equals(mLastUpdateCountry) || now - mLastUpdateCheck >= TimeUnit.HOURS.toMillis(1)))
    {
      mLastUpdateCheck = now;
      mLastUpdateCountry = country;
      RoadUpdateScheduler.checkNow(mContext, providerId());
    }
  }

  enum UpdateResult
  {
    UPDATED,
    SKIPPED,
    RETRY,
    NEEDS_LOGIN
  }

  private final class AutomaticUpdate
  {
    final String country;
    final BooleanSupplier cancelled;
    final CompletableFuture<UpdateResult> completion;
    long importedAt;
    AutomaticUpdate(String country, BooleanSupplier cancelled, CompletableFuture<UpdateResult> completion)
    {
      this.country = country;
      this.cancelled = cancelled;
      this.completion = completion;
    }
    boolean allowed()
    {
      long now = System.currentTimeMillis();
      return !cancelled.getAsBoolean() && configured() && RoadUpdateSettings.enabled(mPrefs, providerId())
   && !RoadUpdateSettings.needsLogin(mPrefs, providerId())
   && country.equals(RoadUpdateSettings.currentCountry(mPrefs, now))
   && RoadUpdateSettings.expired(importedAt, now, RoadUpdateSettings.days(mPrefs, providerId()));
    }
  }

  // Called on main by WorkManager. Network and storage use the same executors and busy owner as manual imports.
  void updateAutomatically(String provider, String expectedCountry, BooleanSupplier cancelled,
                           CompletableFuture<UpdateResult> completion)
  {
    String country = RoadUpdateSettings.currentCountry(mPrefs, System.currentTimeMillis());
    if (!providerId().equals(provider) || !country.matches("[A-Z]{2}") || cancelled.getAsBoolean()
        || (expectedCountry != null && !expectedCountry.equals(country)) || !configured()
        || !RoadUpdateSettings.enabled(mPrefs, providerId()) || RoadUpdateSettings.needsLogin(mPrefs, providerId()))
    {
      completion.complete(UpdateResult.SKIPPED);
      return;
    }
    if (!begin())
    {
      completion.complete(UpdateResult.RETRY);
      return;
    }
    var update = new AutomaticUpdate(country, cancelled, completion);
    mAutomaticUpdate = update;
    mWorker.execute(() -> {
      try
      {
        update.importedAt = mDatabase.importedAt(providerId(), country);
        if (!update.allowed())
          postAutomaticResult(update, UpdateResult.SKIPPED);
        else
          mNetwork.execute(() -> downloadAutomatic(update));
      }
      catch (SQLiteException e)
      {
        postAutomaticResult(update, UpdateResult.RETRY);
      }
    });
  }

  private void downloadAutomatic(AutomaticUpdate update)
  {
    File file = null;
    try
    {
      if (!update.allowed())
      {
        postAutomaticResult(update, UpdateResult.SKIPPED);
        return;
      }
      file = File.createTempFile("road-auto-", ".txt", mContext.getCacheDir());
      downloadExport(update.country, file, false, update::allowed);
      File downloaded = file;
      mWorker.execute(() -> {
        try (InputStream input = new FileInputStream(downloaded))
        {
          if (!update.allowed())
          {
            postAutomaticResult(update, UpdateResult.SKIPPED);
            return;
          }
          mDatabase.importFile(mProvider, update.country, input, () -> !update.allowed());
          publishImport(update.country, () -> completeAutomatic(update, UpdateResult.UPDATED));
        }
        catch (IOException | SQLiteException e)
        {
          postAutomaticResult(update, update.allowed() ? UpdateResult.RETRY : UpdateResult.SKIPPED);
        }
        finally
        {
          downloaded.delete();
        }
      });
    }
    catch (RoadDataProvider.AuthenticationException e)
    {
      mAuthenticated = false;
      if (file != null)
        file.delete();
      postAutomaticResult(update, update.allowed() ? UpdateResult.NEEDS_LOGIN : UpdateResult.SKIPPED);
    }
    catch (IOException e)
    {
      if (file != null)
        file.delete();
      postAutomaticResult(update, update.allowed() ? UpdateResult.RETRY : UpdateResult.SKIPPED);
    }
  }

  private void postAutomaticResult(AutomaticUpdate update, UpdateResult result)
  {
    mMain.post(() -> completeAutomatic(update, result));
  }

  private void completeAutomatic(AutomaticUpdate update, UpdateResult result)
  {
    mAutomaticUpdate = null;
    if (result == UpdateResult.NEEDS_LOGIN)
    {
      mPrefs.edit().putBoolean(RoadUpdateSettings.authKey(providerId()), true).apply();
      RoadUpdateScheduler.sync(mContext, providerId(), configured());
    }
    finish(switch (result)
    {
      case UPDATED -> R.string.road_events_imported;
      case NEEDS_LOGIN -> R.string.road_events_auto_login_required;
      case RETRY -> R.string.road_events_download_failed;
      case SKIPPED -> 0;
    });
    update.completion.complete(result);
  }

  void cancelAutomaticUpdate(CompletableFuture<UpdateResult> completion)
  {
    if (mAutomaticUpdate != null && mAutomaticUpdate.completion == completion)
      mProvider.cancel();
  }

  private boolean begin()
  {
    if (mBusy)
      return false;
    mBusy = true;
    mMessage = R.string.road_events_loading;
    emit();
    return true;
  }
  private void finish(int message)
  {
    mBusy = false;
    mMessage = message;
    emit();
  }
  private void emit()
  {
    mState.setValue(new State(mBusy, mMessage, mCurrentCountry, mMissing, mCountries, mImports));
  }
}
