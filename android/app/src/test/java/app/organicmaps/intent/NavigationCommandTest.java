package app.organicmaps.intent;

import static org.junit.Assert.*;

import org.junit.Test;

public class NavigationCommandTest
{
  @Test
  public void decodesVoiceSearchAndItsLocationHint()
  {
    var command =
        NavigationCommand.parse("om://map_search?text=%D0%90%D0%97%D0%A1+24%2F7&lat=55.75&lon=37.61&locale=ru");
    assertTrue(command.search);
    assertEquals("АЗС 24/7", command.text);
    assertEquals(55.75, command.center.latitude, 0);
    assertEquals(37.61, command.center.longitude, 0);
    assertEquals("ru", command.locale);
  }

  @Test
  public void acceptsTheUnescapedSpacesUsedByTheExistingVoiceClient()
  {
    assertEquals("Красная площадь", NavigationCommand.parse("om://map_search?text=Красная площадь").text);
  }

  @Test
  public void buildsToCoordinatesFromCurrentPositionByDefault()
  {
    var command = NavigationCommand.parse("om://build_route_on_map?lat_to=55.75&lon_to=37.61&start_guidance=1");
    assertFalse(command.search);
    assertNull(command.origin);
    assertTrue(command.startGuidance);
    assertEquals(55.75, command.destination.latitude, 0);
  }

  @Test
  public void preservesSavedPlaceIdsAndPlanningRequests()
  {
    var command = NavigationCommand.parse("om://build_route_on_map?place_to=1234567890123");
    assertEquals("1234567890123", command.place);
    assertFalse(command.startGuidance);
    assertEquals("home", NavigationCommand.parse("om://build_route_on_map?place_to=home").place);
  }

  @Test
  public void rejectsAmbiguousAndInvalidDestinations()
  {
    for (String query : new String[] {"lat_to=91&lon_to=0", "lat_to=NaN&lon_to=0", "lat_to=55",
                                      "lat_to=55&lon_to=37&place_to=home", "lat_to=55&lat_to=56&lon_to=37"})
      assertThrows(IllegalArgumentException.class, () -> NavigationCommand.parse("om://build_route_on_map?" + query));
    assertThrows(IllegalArgumentException.class, () -> NavigationCommand.parse("om://map_search?text="));
  }

  @Test
  public void doesNotHijackExistingApiLinksOrSilentlyDropAlongRoute()
  {
    assertNull(NavigationCommand.parse("om://search?query=park"));
    assertNull(NavigationCommand.parse("geo:55,37"));
    assertTrue(NavigationCommand.parse("om://map_search?text=fuel&along_route=1").alongRoute);
  }
}
