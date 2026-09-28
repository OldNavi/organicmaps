#include "testing/testing.hpp"

#ifdef OMIM_AUTO
#include "drape_frontend/base_renderer.hpp"
#include "drape_frontend/drape_frontend_tests/visual_params_fixture.hpp"
#include "drape_frontend/engine_context.hpp"
#include "drape_frontend/map_data_provider.hpp"
#include "drape_frontend/metaline_manager.hpp"
#include "drape_frontend/read_manager.hpp"

namespace
{
class EmptyRoutine : public threads::IRoutine
{
public:
  void Do() override {}
};

class TileBatchReceiver : public df::BaseRenderer
{
public:
  explicit TileBatchReceiver(df::ThreadsCommutator & commutator)
    : BaseRenderer(df::ThreadsCommutator::ResourceUploadThread,
                   Params(dp::ApiVersion::Invalid, make_ref(&commutator), nullptr, nullptr, {}))
  {
    StartThread();
  }
  ~TileBatchReceiver() override { StopThread(); }
  void Drain()
  {
    while (ProcessSingleMessage(false))
    {}
  }

private:
  std::unique_ptr<threads::IRoutine> CreateRoutine() override { return std::make_unique<EmptyRoutine>(); }
  void RenderFrame() override {}
  void OnContextCreate() override {}
  void OnContextDestroy() override {}
  void AcceptMessage(ref_ptr<df::Message>) override {}
};

}  // namespace

using ReadCoverageFixture = df::test_support::VisualParamsFixture;

UNIT_CLASS_TEST(ReadCoverageFixture, TileReadBatch_CoverageKeysDoNotInvalidateReadGenerations)
{
  df::ThreadsCommutator commutator;
  TileBatchReceiver receiver(commutator);
  df::MapDataProvider model([](auto const &, auto const &, int) {}, [](auto const &, auto const &) {},
                            [](std::string_view) { return true; }, [](auto const &, int) {},
                            [](auto const &, auto) { return false; }, [](auto const &, auto) {});
  df::MetalineManager metalines(make_ref(&commutator), model);
  df::ReadManager reader(make_ref(&commutator), model, false, false, false, dp::BackgroundMode::Default, 0.5f, true,
                         true /* trackTileHistory */);
  df::TileKey const coverageKey(1, 1, 15);
  ScreenBase screen;
  screen.OnSize(0, 0, 256, 256);
  screen.SetFromRect(m2::AnyRectD(coverageKey.GetGlobalRect()));
  df::TTilesCollection tiles{coverageKey};
  reader.UpdateCoverage(screen, false, true, false, tiles, nullptr, make_ref(&metalines));
  TEST(reader.CheckTileKey(coverageKey), ("Viewport keys deliberately carry generation zero"));
  TEST(reader.CheckTileGeneration(df::TileKey(coverageKey, 1, 1)), ());
  for (size_t frame = 0; frame < 20; ++frame)
    reader.UpdateCoverage(screen, false, false, false, tiles, nullptr, make_ref(&metalines));
  TEST(reader.CheckTileGeneration(df::TileKey(coverageKey, 1, 1)), ("Unchanged coverage must not rebuild each frame"));
  reader.UpdateCoverage(screen, false, true, false, tiles, nullptr, make_ref(&metalines));
  TEST(!reader.CheckTileGeneration(df::TileKey(coverageKey, 1, 1)), ("A queued old read must still be rejected"));
  TEST(reader.CheckTileGeneration(df::TileKey(coverageKey, 2, 2)), ());
  reader.Stop();
  metalines.Stop();
  receiver.Drain();
}
#endif
