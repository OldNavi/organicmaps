package app.organicmaps.road;

import android.content.Context;
import android.content.res.Configuration;
import app.organicmaps.R;
import app.organicmaps.sdk.util.StringUtils;
import java.util.Locale;

/** Uses the voice language and the same speed units and rounding as the map. */
final class SpeedCameraAnnouncement
{
  private final Context mBaseContext;
  private Locale mLocale;
  private Context mContext;
  private SpeedLimitWords mWords;

  SpeedCameraAnnouncement(Context context)
  {
    mBaseContext = context;
  }

  String format(double speedMps, Locale locale)
  {
    if (!locale.equals(mLocale))
    {
      mLocale = locale;
      Configuration configuration = new Configuration(mBaseContext.getResources().getConfiguration());
      configuration.setLocale(locale);
      mContext = mBaseContext.createConfigurationContext(configuration);
      // Only parameterize languages with a translated sentence; never mix languages within a warning.
      boolean translated = "ru".equals(locale.getLanguage()) || "en".equals(locale.getLanguage());
      mWords = translated ? new SpeedLimitWords(mContext.getString(R.string.road_warning_number_small),
                                                mContext.getString(R.string.road_warning_number_tens),
                                                mContext.getString(R.string.road_warning_number_hundreds),
                                                "en".equals(locale.getLanguage()))
                          : null;
    }
    // Imported limits are bounded to 400 km/h; reject invalid input before native numeric conversion.
    if (mWords != null && Double.isFinite(speedMps) && speedMps > 0 && speedMps <= SpeedLimitWords.MAX_LIMIT / 3.6)
    {
      int limit = StringUtils.nativeFormatSpeed(speedMps);
      String words = mWords.format(limit);
      if (words != null)
        return mContext.getString(R.string.road_warning_speed_camera_limit, words);
    }
    return mContext.getString(R.string.road_warning_speed_camera);
  }
}
