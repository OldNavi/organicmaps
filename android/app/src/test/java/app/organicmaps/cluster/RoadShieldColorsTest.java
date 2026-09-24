package app.organicmaps.cluster;

import static org.junit.Assert.assertEquals;

import app.organicmaps.sdk.routing.roadshield.RoadShieldType;
import app.organicmaps.sdk.widget.roadshield.RoadShieldDrawable;
import org.junit.Test;

public class RoadShieldColorsTest
{
  @Test
  public void nationalAndInternationalShieldsUseDistinctBackgrounds()
  {
    assertEquals(0xffffffff, RoadShieldDrawable.getTextColor(RoadShieldType.GenericBlue));
    assertEquals(0xff1a5ec1, RoadShieldDrawable.getBackgroundColor(RoadShieldType.GenericBlue));
    assertEquals(0xffffffff, RoadShieldDrawable.getTextColor(RoadShieldType.GenericGreen));
    assertEquals(0xff309302, RoadShieldDrawable.getBackgroundColor(RoadShieldType.GenericGreen));
  }

  @Test
  public void otherShieldTypesKeepTheirContrastingText()
  {
    assertEquals(0xff000000, RoadShieldDrawable.getTextColor(RoadShieldType.GenericWhite));
    assertEquals(0xffffffff, RoadShieldDrawable.getBackgroundColor(RoadShieldType.GenericWhite));
    assertEquals(0xffffd400, RoadShieldDrawable.getTextColor(RoadShieldType.UKHighway));
  }
}
