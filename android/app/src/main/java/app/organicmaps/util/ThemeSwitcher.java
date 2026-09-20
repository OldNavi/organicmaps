package app.organicmaps.util;

import android.annotation.SuppressLint;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiContext;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatDelegate;
import app.organicmaps.MwmApplication;
import app.organicmaps.downloader.DownloaderStatusIcon;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.MapStyle;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.util.Config;

public enum ThemeSwitcher
{
  @SuppressLint("StaticFieldLeak")
  INSTANCE;

  private SynchronizedThemeController mSynchronizedThemeController;

  @SuppressWarnings("NotNullFieldNotInitialized")
  @NonNull
  private Context mContext;

  @Nullable
  private Config.UiTheme mLatestTheme = null;
  private int mSystemUiMode;

  public void initialize(@NonNull Context context)
  {
    if (mSynchronizedThemeController != null)
      return;
    mContext = context.getApplicationContext();
    mSystemUiMode = mContext.getResources().getConfiguration().uiMode;
    mSynchronizedThemeController =
        new SynchronizedThemeController(MwmApplication.from(mContext).getLocationHelper(), this::setTheme);
  }

  public void onConfigurationChanged(@NonNull Configuration config)
  {
    mSystemUiMode = config.uiMode;
    if (mContext != null && MwmApplication.from(mContext).getOrganicMaps().arePlatformAndCoreInitialized())
      synchronizeApplicationTheme();
  }

  /**
   * Updates the application's visual theme to match current user preferences,
   * device settings, and navigation state. Call this method whenever any of
   * these conditions change to maintain proper theme consistency.
   *
   * <p>Also refreshes map styling without requiring an Activity or an active primary renderer.
   * Routing/layer changes can additionally synchronize through {@link #synchronizeMapStyle(Context, boolean)}.
   */
  @androidx.annotation.UiThread
  public void synchronizeApplicationTheme()
  {
    final Config.UiTheme themePreference = Config.UiTheme.getUiThemePreference();
    final boolean isScheduledTheme = themePreference == Config.UiTheme.SCHEDULED;
    final boolean isNavigationAutoDark =
        RoutingController.get().isNavigating() && Config.UiTheme.isAutoDarkNavigationEnabled();

    if (isScheduledTheme || isNavigationAutoDark)
    {
      final var dayTheme = isScheduledTheme ? Config.UiTheme.LIGHT : themePreference;
      mSynchronizedThemeController.synchronizeTimeDependentTheme(dayTheme);
    }
    else
    {
      mSynchronizedThemeController.stop();
      setTheme(themePreference);
    }
  }

  /**
   * Updates the map's visual style to match the current application theme and
   * navigation mode. Call this method when any of the following conditions change:
   *
   * <ul>
   *   <li>Application theme (light/dark mode)</li>
   *   <li>Navigation mode</li>
   *   <li>Outdoor map layer availability</li>
   * </ul>
   *
   * <p>This method must be called on the UI thread. Pass false when the primary renderer is
   * absent or paused; retained graphics and active clusters are refreshed without resuming it.</p>
   *
   * @param context The activity context currently displaying the map
   * @param isRendererActive Whether the OpenGL renderer is currently active
   *                         and the map is visible on screen
   *
   * @see #synchronizeApplicationTheme()
   */
  @androidx.annotation.UiThread
  public void synchronizeMapStyle(@UiContext @NonNull Context context, boolean isRendererActive)
  {
    boolean dark = mLatestTheme == null ? ThemeUtils.isDarkTheme(context) : isDarkTheme(mLatestTheme, mSystemUiMode);
    applyMapStyle(dark, isRendererActive);
  }

  private void applyMapStyle(boolean dark, boolean isRendererActive)
  {
    var mapStyle = calculateMapStyle(dark);
    if (MapStyle.get() != mapStyle)
      setMapStyle(mapStyle, isRendererActive);
  }

  private void setTheme(@NonNull Config.UiTheme theme)
  {
    UiModeManager uiModeManager = (UiModeManager) mContext.getSystemService(Context.UI_MODE_SERVICE);
    switch (theme)
    {
    case LIGHT:
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO);
      AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
      break;
    case DARK:
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES);
      AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
      break;
    case SYSTEM:
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO);
      AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
      break;
    case SCHEDULED:
      throw new IllegalArgumentException("Special case, should be handled differently "
                                         + "and converted to either dark or light");
    }

    if (mLatestTheme != null && mLatestTheme != theme)
    {
      DownloaderStatusIcon.clearCache();
    }
    mLatestTheme = theme;
    if (MwmApplication.from(mContext).getOrganicMaps().arePlatformAndCoreInitialized())
      applyMapStyle(isDarkTheme(theme, mSystemUiMode), false /* isRendererActive */);
  }

  @VisibleForTesting
  static boolean isDarkTheme(@NonNull Config.UiTheme effectiveTheme, int systemUiMode)
  {
    return effectiveTheme == Config.UiTheme.DARK
 || (effectiveTheme == Config.UiTheme.SYSTEM
     && (systemUiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
  }

  private MapStyle calculateMapStyle(boolean dark)
  {
    if (RoutingController.get().isVehicleNavigation())
      return dark ? MapStyle.VehicleDark : MapStyle.VehicleClear;
    else if (Framework.nativeIsOutdoorsLayerEnabled())
      return dark ? MapStyle.OutdoorsDark : MapStyle.OutdoorsClear;
    else
      return dark ? MapStyle.Dark : MapStyle.Clear;
  }

  private void setMapStyle(MapStyle style, boolean isRendererActive)
  {
    // Because of the distinct behavior in auto theme, Android Auto employs its own mechanism for theme switching.
    // For the Android Auto theme switcher, please consult the app.organicmaps.car.util.ThemeUtils module.
    if (MwmApplication.from(mContext).getDisplayManager().isCarDisplayUsed())
      return;
    // Marking also queues a refresh: a configuration change may retain the old map graphics.
    if (isRendererActive)
      MapStyle.set(style);
    else
      MapStyle.mark(style);
  }
}
