#include "testing/testing.hpp"

#ifdef OMIM_AUTO
#include "drape_frontend/drape_frontend_tests/shape_test_fixture.hpp"
#include "drape_frontend/drape_frontend_tests/visual_params_fixture.hpp"
#include "drape_frontend/postprocess_renderer.hpp"
#include "drape_frontend/screen_quad_renderer.hpp"

#include "drape/gl_includes.hpp"

namespace
{
class ScaledTargetTestShape : public df::MapShape
{
public:
  void Draw(ref_ptr<dp::GraphicsContext> context, ref_ptr<dp::Batcher>, ref_ptr<dp::TextureManager>) const override
  {
    dp::Framebuffer target(dp::TextureFormat::RGBA8, true, true);
    target.SetSize(context, 64, 64);
    df::PostprocessRenderer renderer;
    renderer.Init(context, [&target]
    {
      target.Bind();
      return true;
    }, [](ScreenBase const &) {});
    renderer.Resize(context, 64, 64);
    renderer.OnChangedRouteFollowingMode(context, true);

    target.Bind();
    context->SetViewport(0, 0, 64, 64);
    context->SetClearColor(dp::Color::Blue());
    context->Clear(dp::ClearBits::ColorBit, dp::ClearBits::ColorBit);
    context->SetViewport(0, 0, 32, 32);  // Left over from rendering the reduced-size map.

    TEST(renderer.RestoreFrameTarget(context), ());
    context->SetClearColor(dp::Color::White());
    context->Clear(dp::ClearBits::ColorBit | dp::ClearBits::DepthBit, dp::kClearBitsStoreAll);
    uint8_t pixel[4]{};
    glReadPixels(63, 63, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
    TEST_EQUAL(pixel[0], 255, ("Pixels outside the old scissor must not survive into the next frame"));
    TEST_EQUAL(pixel[1], 255, ());
    TEST_EQUAL(pixel[2], 255, ());
  }
};

class MultisampleTargetTestShape : public df::MapShape
{
public:
  void Draw(ref_ptr<dp::GraphicsContext> context, ref_ptr<dp::Batcher>, ref_ptr<dp::TextureManager>) const override
  {
    for (uint32_t samples : {2, 4})
    {
      dp::Framebuffer target(dp::TextureFormat::RGBA8, true, true);
      target.SetSamples(samples);
      target.SetSize(context, 64, 64);
      TEST(target.GetSamples() == 2 || target.GetSamples() == 4, ("A real multisampled target is required"));
      TEST_LESS_OR_EQUAL(target.GetSamples(), samples, ());
      target.Bind();
      context->SetViewport(0, 0, 64, 64);
      context->SetClearColor(dp::Color::Red());
      context->Clear(dp::ClearBits::ColorBit | dp::ClearBits::DepthBit | dp::ClearBits::StencilBit,
                     dp::kClearBitsStoreAll);
      target.Resolve();
      uint8_t pixel[4]{};
      glReadPixels(63, 63, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
      TEST_EQUAL(pixel[0], 255, ("MSAA color must reach the resolved texture"));
      TEST_EQUAL(pixel[1], 0, ());
      TEST_EQUAL(pixel[2], 0, ());
      target.SetSize(context, 32, 32);
      TEST_GREATER_OR_EQUAL(target.GetSamples(), 2, ("Resize recreates multisampled storage"));
    }
  }
};
}  // namespace

using ScaledTargetFixture = df::test_support::VisualParamsFixture;
UNIT_CLASS_TEST(ScaledTargetFixture, ScaledTarget_RestoresScissorBeforeClear)
{
  df::test_support::ShapeTestFixture fixture;
  fixture.Render("Restore native render target", 64, 64,
                 [](auto & shapes) { shapes.AddShape(make_unique_dp<ScaledTargetTestShape>()); });
}

UNIT_CLASS_TEST(ScaledTargetFixture, ScaledTarget_ResolvesHardwareMultisampling)
{
  df::test_support::ShapeTestFixture fixture;
  fixture.Render("Resolve hardware multisampling", 64, 64,
                 [](auto & shapes) { shapes.AddShape(make_unique_dp<MultisampleTargetTestShape>()); });
}
#endif
