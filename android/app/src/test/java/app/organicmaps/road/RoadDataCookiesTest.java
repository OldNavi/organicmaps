package app.organicmaps.road;

import static org.junit.Assert.*;

import java.util.List;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import org.junit.Test;

public class RoadDataCookiesTest
{
  @Test
  public void honorsDomainPathSecureExpiryAndReplacement()
  {
    var jar = new OpenSpeedCamProvider.SessionCookies();
    var url = HttpUrl.get("https://example.test/api/auth/login");
    Cookie first = new Cookie.Builder()
                       .name("session")
                       .value("first")
                       .hostOnlyDomain("example.test")
                       .path("/api")
                       .secure()
                       .httpOnly()
                       .build();
    jar.saveFromResponse(url, List.of(first));
    assertEquals(1, jar.loadForRequest(HttpUrl.get("https://example.test/api/export")).size());
    assertTrue(jar.loadForRequest(HttpUrl.get("http://example.test/api/export")).isEmpty());
    assertTrue(jar.loadForRequest(HttpUrl.get("https://other.test/api/export")).isEmpty());
    assertTrue(jar.loadForRequest(HttpUrl.get("https://example.test/elsewhere")).isEmpty());
    Cookie next =
        new Cookie.Builder().name("session").value("next").hostOnlyDomain("example.test").path("/api").secure().build();
    jar.saveFromResponse(url, List.of(next));
    assertEquals("next", jar.loadForRequest(url).get(0).value());
    Cookie expired =
        new Cookie.Builder().name("session").value("").hostOnlyDomain("example.test").path("/api").expiresAt(1).build();
    jar.saveFromResponse(url, List.of(expired));
    assertTrue(jar.loadForRequest(url).isEmpty());
    jar.saveFromResponse(url, List.of(first));
    jar.clear();
    assertTrue(jar.loadForRequest(url).isEmpty());
  }
}
