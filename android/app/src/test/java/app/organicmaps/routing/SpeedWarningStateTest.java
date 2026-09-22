package app.organicmaps.routing;

import static org.junit.Assert.*;

import org.junit.Test;

public class SpeedWarningStateTest
{
  @Test
  public void offsetUsesKilometersPerHourAndOnlyStrictExceedingAlerts()
  {
    assertFalse(SpeedWarningState.isExceeded(76 / 3.6, 60 / 3.6, 16));
    assertTrue(SpeedWarningState.isExceeded(77 / 3.6, 60 / 3.6, 16));
    assertFalse(SpeedWarningState.isExceeded(99 / 3.6, 60 / 3.6, 40));
    assertTrue(SpeedWarningState.isExceeded(61 / 3.6, 60 / 3.6, 0));
    assertFalse(SpeedWarningState.isExceeded(0, 60 / 3.6, 0));
  }

  @Test
  public void unavailableSpeedOrLimitNeverAlerts()
  {
    for (double speed : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1})
      assertFalse(SpeedWarningState.isExceeded(speed, 15, 0));
    for (double limit : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1, 0})
      assertFalse(SpeedWarningState.isExceeded(30, limit, 0));
  }

  @Test
  public void oneAudioAlertPerSustainedEpisode()
  {
    SpeedWarningState state = new SpeedWarningState();
    assertFalse(state.update(30, 20, 0, 100));
    assertFalse(state.update(30, 20, 0, 1099));
    assertTrue(state.update(30, 20, 0, 1100));
    state.notified(1100);
    assertFalse(state.update(30, 20, 0, 1200));
    assertFalse(state.update(30, 20, 0, 60000));
  }

  @Test
  public void transientPeakDoesNotSpeak()
  {
    SpeedWarningState state = new SpeedWarningState();
    assertFalse(state.update(21, 20, 0, 100));
    assertFalse(state.update(20, 20, 0, 900));
    assertFalse(state.update(21, 20, 0, 1000));
    assertTrue(state.update(21, 20, 0, 2000));
  }

  @Test
  public void jitterDoesNotRearmButSustainedSlowingDoes()
  {
    SpeedWarningState state = new SpeedWarningState();
    state.update(30, 20, 0, 0);
    assertTrue(state.update(30, 20, 0, 1000));
    state.notified(1000);
    state.update(19.8, 20, 0, 32000);
    state.update(19.8, 20, 0, 36000);
    assertFalse(state.update(30, 20, 0, 37000));
    assertFalse(state.update(30, 20, 0, 38000));
    state.update(19, 20, 0, 40000);
    state.update(19, 20, 0, 43000);
    assertFalse(state.update(30, 20, 0, 44000));
    assertTrue(state.update(30, 20, 0, 45000));
  }

  @Test
  public void changingLimitsOrSettingsCannotSpamAudio()
  {
    SpeedWarningState state = new SpeedWarningState();
    state.update(30, 20, 0, 0);
    assertTrue(state.update(30, 20, 0, 1000));
    state.notified(1000);
    assertFalse(state.update(30, 15, 0, 2000));
    assertFalse(state.update(30, 15, 0, 3000));
    assertFalse(state.update(30, 15, 10, 4000));
    assertFalse(state.update(30, 15, 10, 5000));
    assertTrue(state.update(30, 15, 10, 31000));
    assertFalse(state.update(30, 30, 0, 32000));
  }

  @Test
  public void staleMeasurementsCancelPendingWarning()
  {
    SpeedWarningState state = new SpeedWarningState();
    state.update(30, 20, 0, 0);
    assertFalse(state.update(Double.NaN, 20, 0, 1000));
    assertFalse(state.update(30, 0, 0, 2000));
    assertFalse(state.update(30, 20, 0, 3000));
    assertTrue(state.update(30, 20, 0, 4000));
  }

  @Test
  public void busyAudioCanRetryOnlyWhileStillSpeeding()
  {
    SpeedWarningState state = new SpeedWarningState();
    state.update(30, 20, 0, 0);
    assertTrue(state.update(30, 20, 0, 1000));
    assertTrue(state.update(30, 20, 0, 1500));
    assertFalse(state.update(19, 20, 0, 2000));
  }
}
