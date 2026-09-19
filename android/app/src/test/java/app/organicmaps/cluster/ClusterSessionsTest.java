package app.organicmaps.cluster;

import static org.junit.Assert.*;

import org.junit.Test;

public class ClusterSessionsTest
{
  @Test
  public void connectionsHaveIndependentOwnersAndLifetimes()
  {
    ClusterSessions sessions = new ClusterSessions();
    var first = sessions.connect(1001, 2, 16);
    var second = sessions.connect(1002, 3, 18);
    assertFalse(sessions.disconnect(1002, 2));
    assertTrue(sessions.isCurrent(first));
    assertTrue(sessions.disconnect(1001, 2));
    assertFalse(sessions.isCurrent(first));
    assertTrue(sessions.isCurrent(second));
    assertFalse(sessions.isEmpty());
    sessions.removeDisplay(3);
    assertTrue(sessions.isEmpty());
  }

  @Test
  public void reconnectAndHideCancelPendingPresentationCreation()
  {
    ClusterSessions sessions = new ClusterSessions();
    var oldRequest = sessions.connect(1001, 2, 16);
    var newRequest = sessions.connect(1001, 2, 17);
    assertFalse(sessions.isCurrent(oldRequest));
    assertTrue(sessions.isCurrent(newRequest));
    assertTrue(sessions.disconnect(1001, 2));
    assertFalse(sessions.isCurrent(newRequest));
  }

  @Test
  public void cannotStealDisplayOrAddressDefaultDisplay()
  {
    ClusterSessions sessions = new ClusterSessions();
    var request = sessions.connect(1001, 2, 16);
    assertThrows(SecurityException.class, () -> sessions.connect(1002, 2, 16));
    assertTrue(sessions.isCurrent(request));
    assertThrows(IllegalArgumentException.class, () -> sessions.connect(1001, 0, 16));
    assertThrows(IllegalArgumentException.class, () -> sessions.connect(1001, 3, 21));
  }

  @Test
  public void supportsMoreThanOneOrTwoConnections()
  {
    ClusterSessions sessions = new ClusterSessions();
    for (int display = 1; display <= 16; ++display)
      sessions.connect(1001, display, 16);
    for (int display = 1; display <= 16; ++display)
      assertTrue(sessions.disconnect(1001, display));
    assertTrue(sessions.isEmpty());
  }
  @Test
  public void aDisplayCanSwitchBetweenFixedZoomAndAutoWithoutChangingOtherDisplays()
  {
    ClusterSessions sessions = new ClusterSessions();
    var other = sessions.connect(1001, 3, 15);
    sessions.connect(1001, 2, 16);
    var automatic = sessions.connect(1001, 2, 0);
    assertEquals(0, automatic.zoom);
    assertTrue(sessions.isCurrent(other));
    assertEquals(15, other.zoom);
    assertEquals(18, sessions.connect(1001, 2, 18).zoom);
  }
}
