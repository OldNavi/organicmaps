package app.organicmaps.road;

import static org.junit.Assert.*;

import okhttp3.HttpUrl;
import org.junit.Test;

public class RoadEventSourceUrlTest
{
  @Test
  public void cameraLinkSelectsSourceRecordAndCentersMap()
  {
    var provider = new OpenSpeedCamProvider();
    var url = HttpUrl.get(provider.eventUrl("5a3bc3ce5664fe372a347637", 55.752667, 37.584024));
    assertEquals("https", url.scheme());
    assertEquals("openspeedcam.net", url.host());
    assertEquals("5a3bc3ce5664fe372a347637", url.queryParameter("point"));
    assertEquals("37.584024,55.752667", url.queryParameter("center"));
    assertEquals("17", url.queryParameter("zoom"));
  }

  @Test
  public void identityCannotInjectQueryParameters()
  {
    var provider = new OpenSpeedCamProvider();
    var url = HttpUrl.get(provider.eventUrl("id&zoom=1#fragment", 55, 38));
    assertEquals("id&zoom=1#fragment", url.queryParameter("point"));
    assertEquals("17", url.queryParameter("zoom"));
    assertNull(url.fragment());
  }
}
