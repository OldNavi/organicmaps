package app.organicmaps.road;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.road.RoadEventAhead;
import app.organicmaps.sdk.road.RoadEventKind;
import app.organicmaps.sdk.settings.UnitLocale;
import app.organicmaps.settings.SpeedWarningSettings;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class SpeedCameraAnnouncementTest
{
  private static final Locale RUSSIAN = Locale.forLanguageTag("ru");
  private int mOriginalUnits;

  @BeforeClass
  public static void initializeCore() throws Exception
  {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var app = MwmApplication.from(instrumentation.getTargetContext());
    var ready = new CountDownLatch(1);
    instrumentation.runOnMainSync(() -> {
      try
      {
        if (!app.initOrganicMaps(ready::countDown))
          ready.countDown();
      }
      catch (Exception error)
      {
        throw new AssertionError(error);
      }
    });
    assertTrue(ready.await(30, TimeUnit.SECONDS));
  }

  @Before
  public void saveUnits()
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      mOriginalUnits = UnitLocale.getUnits();
      UnitLocale.setUnits(UnitLocale.UNITS_METRIC);
    });
  }

  @After
  public void restoreUnits()
  {
    setUnits(mOriginalUnits);
  }

  private static void setUnits(int units)
  {
    // Changing units also updates RoutingSession, which belongs to the UI thread.
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> UnitLocale.setUnits(units));
  }

  private static SpeedCameraAnnouncement announcement(Locale uiLanguage)
  {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Configuration configuration = new Configuration(context.getResources().getConfiguration());
    configuration.setLocale(uiLanguage);
    return new SpeedCameraAnnouncement(context.createConfigurationContext(configuration));
  }

  @Test
  public void russianLimitsAreSpelledOut()
  {
    var text = announcement(Locale.ENGLISH);
    assertEquals("Впереди камера на шестьдесят", text.format(60 / 3.6, RUSSIAN));
    assertEquals("Впереди камера на девяносто", text.format(90 / 3.6, RUSSIAN));
    assertEquals("Впереди камера на сто десять", text.format(110 / 3.6, RUSSIAN));
    assertEquals("Впереди камера на сто тридцать", text.format(130 / 3.6, RUSSIAN));
    assertEquals("Впереди камера на двадцать один", text.format(21 / 3.6, RUSSIAN));
    assertEquals("Впереди камера на четыреста", text.format(400 / 3.6, RUSSIAN));
  }

  @Test
  public void voiceLanguageOverridesUiAndChangesImmediately()
  {
    var text = announcement(RUSSIAN);
    assertEquals("Speed camera ahead, limit sixty", text.format(60 / 3.6, Locale.US));
    assertEquals("Впереди камера на шестьдесят", text.format(60 / 3.6, RUSSIAN));
    assertEquals("Speed camera ahead, limit sixty", text.format(60 / 3.6, Locale.UK));
    assertEquals("Speed camera ahead", text.format(60 / 3.6, Locale.GERMAN));
  }

  @Test
  public void limitsUseSelectedSpeedUnits()
  {
    var text = announcement(Locale.ENGLISH);
    setUnits(UnitLocale.UNITS_FOOT);
    assertEquals("Speed camera ahead, limit sixty", text.format(60 * 0.44704, Locale.US));
    assertEquals("Speed camera ahead, limit thirty-seven", text.format(60 / 3.6, Locale.US));
    assertEquals("Впереди камера на тридцать семь", text.format(60 / 3.6, RUSSIAN));
    setUnits(UnitLocale.UNITS_METRIC);
    assertEquals("Впереди камера на шестьдесят", text.format(60 / 3.6, RUSSIAN));
  }

  @Test
  public void missingOrInvalidLimitKeepsGenericWarning()
  {
    var text = announcement(Locale.ENGLISH);
    for (double limit : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.01,
                                      401 / 3.6, Double.MAX_VALUE})
    {
      assertEquals("Впереди камера контроля скорости", text.format(limit, RUSSIAN));
      assertEquals("Speed camera ahead", text.format(limit, Locale.US));
    }
  }

  @Test
  public void offsetChangesEligibilityButNotSpokenLimit()
  {
    var camera = new RoadEventAhead("test-camera", RoadEventKind.CAMERA, 100, 60 / 3.6, 55, 37);
    int visible = 1 << RoadEventKind.CAMERA;
    assertTrue(RoadEventWarningPolicy.isEligible(camera, SpeedWarningSettings.IMPORTANT, visible, 77 / 3.6, 16));
    assertEquals("Впереди камера на шестьдесят", announcement(RUSSIAN).format(camera.speedMps(), RUSSIAN));
  }
}
