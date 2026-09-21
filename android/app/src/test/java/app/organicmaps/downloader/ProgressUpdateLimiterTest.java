package app.organicmaps.downloader;

import static org.junit.Assert.*;

import org.junit.Test;

public class ProgressUpdateLimiterTest
{
  @Test
  public void downloadBurstDoesNotFloodNotificationsOrUi()
  {
    ProgressUpdateLimiter notifications = new ProgressUpdateLimiter(1000);
    ProgressUpdateLimiter ui = new ProgressUpdateLimiter(250);
    int notificationsSent = 0, uiUpdates = 0;
    for (int milliseconds = 0; milliseconds < 10000; milliseconds += 10)
    {
      if (notifications.shouldUpdate(milliseconds))
        ++notificationsSent;
      if (ui.shouldUpdate(milliseconds))
        ++uiUpdates;
    }
    assertEquals(10, notificationsSent);
    assertEquals(40, uiUpdates);
    assertTrue(notifications.shouldUpdate(10000));
  }

  @Test
  public void progressSupportsMapsLargerThanTwoGigabytesAndUnknownSizes()
  {
    assertEquals(5000, DownloaderNotifier.progressUnits(10_000_000_000L, 5_000_000_000L));
    assertEquals(10000, DownloaderNotifier.progressUnits(100, 101));
    assertEquals(0, DownloaderNotifier.progressUnits(100, -1));
    assertEquals(0, DownloaderNotifier.progressUnits(0, 100));
    assertEquals(0, DownloaderNotifier.progressUnits(-1, 0));
  }
}
