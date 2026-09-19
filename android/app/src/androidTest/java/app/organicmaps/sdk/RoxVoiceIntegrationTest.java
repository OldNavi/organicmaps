package app.organicmaps.sdk;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.sound.RoxVoice;
import app.organicmaps.sdk.sound.RoxVoiceDefaults;
import app.organicmaps.sdk.sound.TtsPlayer;
import app.organicmaps.sdk.util.Config;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class RoxVoiceIntegrationTest
{
  @Test
  public void recognizesRoxPersistPropertyAsTheAutoFlavorDefault()
  {
    var app = (MwmApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    assumeTrue(RoxVoice.isAvailable(app));
    assertTrue(RoxVoiceDefaults.shouldSelect("auto", false));
    assertFalse(RoxVoiceDefaults.shouldSelect("auto", true));
    assertFalse(RoxVoiceDefaults.shouldSelect("google", false));
  }

  @Test
  public void connectsToTheStockVoiceManagerWithoutChangingCarVoiceSettings() throws Exception
  {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assumeTrue(RoxVoice.isAvailable(context));
    CountDownLatch ready = new CountDownLatch(1);
    try (RoxVoice voice = new RoxVoice(context, available -> {
           if (available)
             ready.countDown();
         }))
    {
      assertTrue("Stock ROX VoiceManager did not become ready", ready.await(20, TimeUnit.SECONDS));
      assertTrue(voice.isReady());
      // Speech itself is exercised with the existing voice-test button, not unsolicited car audio.
    }
  }
  @Test
  public void voiceSettingsCanSelectStockBackendAndRestoreAndroidBackend() throws Exception
  {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    MwmApplication app = (MwmApplication) instrumentation.getTargetContext().getApplicationContext();
    assumeTrue(RoxVoice.isAvailable(app));
    CountDownLatch initialized = new CountDownLatch(1);
    instrumentation.runOnMainSync(() -> {
      try
      {
        if (!app.initOrganicMaps(initialized::countDown))
          initialized.countDown();
      }
      catch (IOException e)
      {
        throw new AssertionError(e);
      }
    });
    assertTrue(initialized.await(30, TimeUnit.SECONDS));
    boolean[] previous = new boolean[1];
    instrumentation.runOnMainSync(() -> previous[0] = Config.TTS.useRoxVoice());
    try
    {
      instrumentation.runOnMainSync(() -> TtsPlayer.INSTANCE.setUseRoxVoice(true));
      AtomicReference<TtsPlayer.State> state = new AtomicReference<>();
      long deadline = SystemClock.elapsedRealtime() + 20000;
      do
      {
        instrumentation.runOnMainSync(() -> state.set(TtsPlayer.getState()));
        if (state.get().isReady())
          break;
        Thread.sleep(100);
      }
      while (SystemClock.elapsedRealtime() < deadline);
      assertTrue("ROX backend state: " + state.get(), state.get().isReady());
      instrumentation.runOnMainSync(() -> {
        assertTrue(Config.TTS.useRoxVoice());
        assertFalse(TtsPlayer.INSTANCE.refreshLanguages().isEmpty());
        TtsPlayer.INSTANCE.initialize(app);
        assertTrue(TtsPlayer.getState().isReady());
      });
    }
    finally
    {
      instrumentation.runOnMainSync(() -> TtsPlayer.INSTANCE.setUseRoxVoice(previous[0]));
    }
  }
}
