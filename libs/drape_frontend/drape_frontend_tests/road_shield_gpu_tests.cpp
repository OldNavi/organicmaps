#include "testing/testing.hpp"

#include "drape_frontend/colored_symbol_shape.hpp"
#include "drape_frontend/drape_frontend_tests/shape_test_fixture.hpp"
#include "drape_frontend/drape_frontend_tests/visual_params_fixture.hpp"

namespace road_shield_gpu_tests
{
using df::test_support::VisualParamsFixture;

UNIT_CLASS_TEST(VisualParamsFixture, RoadShieldGPU_HiddenBackgroundDoesNotDraw)
{
  for (int mode : {0, 1, 2})
  {
    df::test_support::ShapeTestFixture fixture;
    fixture.Render("Road shield background visibility", 160, 100, [&](auto & f)
    {
      df::ColoredSymbolViewParams params;
      params.m_tileCenter = {0, 0};
      params.m_depth = 0;
      params.m_depthLayer = df::DepthLayer::OverlayLayer;
      params.m_depthTestEnabled = false;
      params.m_color = dp::Color(0, 160, 0);
      params.m_shape = df::ColoredSymbolViewParams::Shape::RoundedRectangle;
      params.m_sizeInPixels = {90, 36};
      params.m_radiusInPixels = 4;
      params.m_minVisibleScale = 0;
      if (mode == 2)
        params.m_markId = (kml::kExternalMarkGroupId << 60) | 42;
      f.AddShape(make_unique_dp<df::ColoredSymbolShape>(m2::PointD(0, 0), params, df::TileKey(0, 0, 1), 0, mode != 0));
    });
    auto const & image = fixture.GetLastImage();
    TEST(!image.isNull(), ("GPU context is required for this regression"));
    size_t green = 0;
    for (int y = 0; y < image.height(); ++y)
      for (int x = 0; x < image.width(); ++x)
      {
        auto const pixel = image.pixelColor(x, y);
        green += pixel.green() > pixel.red() + 30 && pixel.green() > pixel.blue() + 30;
      }
    if (mode == 1)
      TEST_EQUAL(green, 0, ("A rejected map shield must not keep drawing an unindexed background"));
    else
      TEST_GREATER(green, 100, ("Geometry without displacement and valid external symbols remain visible", mode));
  }
}
}  // namespace road_shield_gpu_tests
