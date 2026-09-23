package app.organicmaps.routing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import androidx.preference.ListPreference;
import androidx.preference.SeekBarPreference;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.sdk.sound.NavigationWarningPlayer;
import app.organicmaps.sdk.sound.TtsPlayer;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.settings.SettingsActivity;
import app.organicmaps.settings.SettingsPrefsFragment;
import app.organicmaps.settings.SpeedWarningSettings;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class SpeedWarningSettingsTest
{
  private static void main(Runnable action)
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
  }

  @Test
  public void offsetAndAllAudioModesPersistAndApplyImmediately() throws Exception
  {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    MwmApplication app = MwmApplication.from(context);
    CountDownLatch ready = new CountDownLatch(1);
    main(() -> {
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
    SharedPreferences prefs = MwmApplication.prefs(context);
    boolean hadOffset = prefs.contains(SpeedWarningSettings.OFFSET_KEY);
    boolean hadMode = prefs.contains(SpeedWarningSettings.MODE_KEY);
    boolean hadLevel = prefs.contains(SpeedWarningSettings.LEVEL_KEY);
    int oldLevel = SpeedWarningSettings.level(context);
    int oldOffset = prefs.getInt(SpeedWarningSettings.OFFSET_KEY, 0);
    String oldMode = prefs.getString(SpeedWarningSettings.MODE_KEY, SpeedWarningSettings.VOICE);
    AtomicReference<SettingsActivity> activity = new AtomicReference<>();
    try
    {
      context.startActivity(new Intent(context, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long deadline = SystemClock.elapsedRealtime() + 10000;
      while (activity.get() == null && SystemClock.elapsedRealtime() < deadline)
      {
        main(() -> {
          for (Activity current : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (current instanceof SettingsActivity settings)
              activity.set(settings);
        });
        Thread.sleep(100);
      }
      assertNotNull(activity.get());
      main(() -> {
        SettingsPrefsFragment fragment =
            (SettingsPrefsFragment) activity.get().getSupportFragmentManager().getFragments().get(0);
        SeekBarPreference offset = fragment.findPreference(SpeedWarningSettings.OFFSET_KEY);
        assertNotNull(offset);
        assertEquals(0, offset.getMin());
        assertEquals(40, offset.getMax());
        assertTrue(offset.callChangeListener(16));
        offset.setValue(16);
        assertEquals(16, SpeedWarningSettings.offsetKmh(context));
        assertFalse(SpeedWarningController.isExceeded(context, 76 / 3.6, 60 / 3.6));
        assertTrue(SpeedWarningController.isExceeded(context, 77 / 3.6, 60 / 3.6));
        assertTrue(offset.getSummary().toString().contains("+16"));
        ListPreference mode = fragment.findPreference(SpeedWarningSettings.MODE_KEY);
        assertNotNull(mode);
        for (String value : new String[] {SpeedWarningSettings.VOICE, SpeedWarningSettings.SOUND})
        {
          assertTrue(mode.callChangeListener(value));
          mode.setValue(value);
          assertEquals(value, SpeedWarningSettings.mode(context));
          assertTrue("Muting audio must not disable visual warnings",
                     SpeedWarningController.isExceeded(context, 77 / 3.6, 60 / 3.6));
        }
        ListPreference level = fragment.findPreference(SpeedWarningSettings.LEVEL_KEY);
        assertNotNull(level);
        for (int value : new int[] {SpeedWarningSettings.OFF, SpeedWarningSettings.IMPORTANT, SpeedWarningSettings.ALL})
        {
          assertTrue(level.callChangeListener(Integer.toString(value)));
          level.setValue(Integer.toString(value));
          assertEquals(value, SpeedWarningSettings.level(context));
          assertTrue(SpeedWarningController.isExceeded(context, 77 / 3.6, 60 / 3.6));
        }
        fragment.scrollToPreference(SpeedWarningSettings.OFFSET_KEY);
      });
      Thread.sleep(500);
      AtomicReference<Bitmap> captured = new AtomicReference<>();
      main(() -> {
        // Draw our own view: the user can switch other apps on the head unit during this test.
        var fragment = (SettingsPrefsFragment) activity.get().getSupportFragmentManager().getFragments().get(0);
        var view = fragment.getListView();
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(activity.get().getColor(R.color.bg_cards));
        view.draw(canvas);
        captured.set(bitmap);
      });
      Bitmap screenshot = captured.get();
      try (FileOutputStream output =
               new FileOutputStream(new File(context.getCacheDir(), "speed-warning-settings.png")))
      {
        assertNotNull(screenshot);
        screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
      }
      screenshot.recycle();
    }
    finally
    {
      main(() -> {
        SharedPreferences.Editor editor = prefs.edit();
        if (hadOffset)
          editor.putInt(SpeedWarningSettings.OFFSET_KEY, oldOffset);
        else
          editor.remove(SpeedWarningSettings.OFFSET_KEY);
        if (hadMode)
          editor.putString(SpeedWarningSettings.MODE_KEY, oldMode);
        else
          editor.remove(SpeedWarningSettings.MODE_KEY);
        if (hadLevel)
          editor.putInt(SpeedWarningSettings.LEVEL_KEY, oldLevel);
        else
          editor.remove(SpeedWarningSettings.LEVEL_KEY);
        editor.apply();
        if (activity.get() != null)
          activity.get().finish();
      });
    }
  }

  @Test
  public void voiceUsesSelectedEngineAndDoesNotInterruptSpeech() throws Exception
  {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    CountDownLatch ready = new CountDownLatch(1);
    main(() -> {
      try
      {
        if (!MwmApplication.from(context).initOrganicMaps(ready::countDown))
          ready.countDown();
      }
      catch (Exception error)
      {
        throw new AssertionError(error);
      }
    });
    assertTrue(ready.await(30, TimeUnit.SECONDS));
    boolean enabled = Config.TTS.isEnabled();
    try
    {
      main(() -> TtsPlayer.setEnabled(true));
      long deadline = SystemClock.elapsedRealtime() + 15000;
      while (TtsPlayer.getState() != TtsPlayer.State.READY_ON && SystemClock.elapsedRealtime() < deadline)
        Thread.sleep(50);
      assertEquals(TtsPlayer.State.READY_ON, TtsPlayer.getState());
      main(() -> {
        assertTrue(TtsPlayer.INSTANCE.speakWarning(context.getString(R.string.auto_speed_warning_voice)));
        assertFalse("A second warning must not interrupt the first", TtsPlayer.INSTANCE.speakWarning("test"));
      });
      deadline = SystemClock.elapsedRealtime() + 15000;
      while (TtsPlayer.INSTANCE.isSpeaking() && SystemClock.elapsedRealtime() < deadline)
        Thread.sleep(50);
      assertFalse("Speech must finish and permit future alerts", TtsPlayer.INSTANCE.isSpeaking());
      main(() -> {
        TtsPlayer.setEnabled(false);
        assertFalse(TtsPlayer.INSTANCE.speakWarning("test"));
      });
    }
    finally
    {
      main(() -> {
        TtsPlayer.INSTANCE.stop();
        TtsPlayer.setEnabled(enabled);
      });
    }
  }

  @Test
  public void shortSignalPlaysOnNavigationUsage() throws Exception
  {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    try (var asset = context.getAssets().openFd("overspeed_warning.mp3");
         MediaMetadataRetriever metadata = new MediaMetadataRetriever())
    {
      metadata.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
      long duration = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
      assertTrue("Expected the short navigation warning signal", duration >= 500 && duration <= 600);
    }
    AtomicReference<NavigationWarningPlayer> player = new AtomicReference<>();
    try
    {
      main(() -> {
        player.set(new NavigationWarningPlayer(context));
        assertTrue(player.get().play("overspeed_warning.mp3"));
      });
      AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
      boolean navigationSound = false;
      long deadline = SystemClock.elapsedRealtime() + 2000;
      while (!navigationSound && SystemClock.elapsedRealtime() < deadline)
      {
        navigationSound = audio.getActivePlaybackConfigurations().stream().anyMatch(
            playback
            -> playback.getAudioAttributes().getUsage() == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                   && playback.getAudioAttributes().getContentType() == AudioAttributes.CONTENT_TYPE_SONIFICATION);
        Thread.sleep(10);
      }
      assertTrue("Signal must actually play with navigation audio attributes", navigationSound);
      Thread.sleep(700);
      assertFalse(audio.getActivePlaybackConfigurations().stream().anyMatch(
          playback
          -> playback.getAudioAttributes().getUsage() == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                 && playback.getAudioAttributes().getContentType() == AudioAttributes.CONTENT_TYPE_SONIFICATION));
      var decoderField = NavigationWarningPlayer.class.getDeclaredField("mPlayer");
      decoderField.setAccessible(true);
      Object decoder = decoderField.get(player.get());
      assertNotNull("Prepared decoder must survive completion", decoder);
      main(() -> assertTrue(player.get().play("overspeed_warning.mp3")));
      assertSame("Repeating an alert must reuse its MediaPlayer", decoder, decoderField.get(player.get()));
      Thread.sleep(700);
    }
    finally
    {
      main(() -> {
        if (player.get() != null)
          player.get().close();
      });
    }
  }
}
