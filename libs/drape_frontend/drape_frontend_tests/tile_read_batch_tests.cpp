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
  size_t m_messages = 0, m_geometry = 0, m_overlays = 0;
  bool m_onlyBatches = true;
  df::TileKey m_key;

private:
  std::unique_ptr<threads::IRoutine> CreateRoutine() override { return std::make_unique<EmptyRoutine>(); }
  void RenderFrame() override {}
  void OnContextCreate() override {}
  void OnContextDestroy() override {}
  void AcceptMessage(ref_ptr<df::Message> message) override
  {
    if (message->GetType() != df::Message::Type::ReadTileBatch)
    {
      TEST(!m_onlyBatches, ());
      return;
    }
    ref_ptr<df::TileReadBatchMessage> batch = message;
    ++m_messages;
    m_key = batch->GetKey();
    m_geometry += batch->m_geometry.size();
    m_overlays += batch->m_overlays.size();
  }
};

class CountedShape : public df::MapShape
{
public:
  explicit CountedShape(size_t & destroyed) : m_destroyed(destroyed) {}
  ~CountedShape() override { ++m_destroyed; }
  void Draw(ref_ptr<dp::GraphicsContext>, ref_ptr<dp::Batcher>, ref_ptr<dp::TextureManager>) const override {}

private:
  size_t & m_destroyed;
};
}  // namespace

UNIT_TEST(TileReadBatch_CoalescesGeometryAndDropsCancelledTile)
{
  df::ThreadsCommutator commutator;
  TileBatchReceiver receiver(commutator);
  df::TileKey const key(df::TileKey(1, 1, 15), 7, 9);
  df::EngineContext context(key, make_ref(&commutator), nullptr, nullptr, {}, false, false, false, 0,
                            dp::BackgroundMode::Default, 0.5f);
  size_t destroyed = 0;
  auto flush = [&]
  {
    for (size_t i = 0; i < 1000; ++i)
    {
      df::TMapShapes shapes;
      shapes.push_back(make_unique_dp<CountedShape>(destroyed));
      context.Flush(std::move(shapes));
    }
    df::TMapShapes overlays;
    overlays.push_back(make_unique_dp<CountedShape>(destroyed));
    context.FlushOverlays(std::move(overlays));
  };
  context.BeginReadTile();
  flush();
  receiver.Drain();
  TEST_EQUAL(receiver.m_messages, 0, ("Individual features must not fill the upload queue"));
  context.EndReadTile();
  receiver.Drain();
  TEST_EQUAL(receiver.m_messages, 1, ());
  TEST_EQUAL(receiver.m_geometry, 1000, ());
  TEST_EQUAL(receiver.m_overlays, 1, ());
  TEST(receiver.m_key.EqualStrict(key), ("Retain generations for validation before upload"));
  TEST_EQUAL(destroyed, 1001, ());

  context.BeginReadTile();
  flush();
  context.EndReadTile(true);
  receiver.Drain();
  TEST_EQUAL(receiver.m_messages, 1, ("Cancelled reads must release CPU geometry without uploading it"));
  TEST_EQUAL(destroyed, 2002, ());
}

using ReadCoverageFixture = df::test_support::VisualParamsFixture;

UNIT_CLASS_TEST(ReadCoverageFixture, TileReadBatch_CoverageKeysDoNotInvalidateReadGenerations)
{
  df::ThreadsCommutator commutator;
  TileBatchReceiver receiver(commutator);
  receiver.m_onlyBatches = false;
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
