#include "testing/testing.hpp"

#ifdef OMIM_AUTO
#include "drape_frontend/drape_frontend_tests/shape_test_fixture.hpp"
#include "drape_frontend/drape_frontend_tests/visual_params_fixture.hpp"
#include "drape_frontend/my_position.hpp"

#include "geometry/mercator.hpp"

namespace
{
class PrecisePositionTestShape : public df::MapShape
{
public:
  void Draw(ref_ptr<dp::GraphicsContext> context, ref_ptr<dp::Batcher>,
            ref_ptr<dp::TextureManager> textures) const override
  {
    df::MyPosition position(context, textures);
    auto const center = mercator::FromLatLon(55.75732, 37.61313);
    ScreenBase screen;
    screen.OnSize(0, 0, 1920, 660);
    screen.SetFromRect(
        m2::AnyRectD(m2::RectD(center.x - 0.001, center.y - 0.00035, center.x + 0.001, center.y + 0.00035)));
    double roundedError = 0;
    for (int step = 0; step < 300; ++step)
    {
      auto const point = center + m2::PointD(step * 1e-7, step * 1.1e-7);
      position.SetPosition(df::MyPosition::PositionPoint(point));
      auto const expected = screen.GtoP(point);
      auto const actual = screen.GtoP(position.GetPosition());
      TEST_LESS((actual - expected).Length(), 1e-4, ("World-coordinate rounding must not move the arrow"));
      roundedError = std::max(roundedError, (screen.GtoP(m2::PointD(m2::PointF(point))) - expected).Length());
    }
    TEST_GREATER(roundedError, 0.5, ("This viewport must expose the former sub-meter quantization"));
  }
};
}  // namespace

using PositionPrecisionFixture = df::test_support::VisualParamsFixture;
UNIT_CLASS_TEST(PositionPrecisionFixture, MyPosition_PreservesHighZoomCoordinates)
{
  df::test_support::ShapeTestFixture fixture;
  fixture.Render("Precise navigation position", 64, 64,
                 [](auto & shapes) { shapes.AddShape(make_unique_dp<PrecisePositionTestShape>()); });
}
#endif
