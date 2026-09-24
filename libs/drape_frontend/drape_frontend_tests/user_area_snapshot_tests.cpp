#include "testing/testing.hpp"

#include "drape_frontend/drape_frontend_tests/shape_test_fixture.hpp"
#include "drape_frontend/drape_frontend_tests/visual_params_fixture.hpp"
#include "drape_frontend/tile_utils.hpp"
#include "drape_frontend/user_mark_generator.hpp"

namespace
{
class SnapshotTestShape : public df::MapShape
{
public:
  void Draw(ref_ptr<dp::GraphicsContext> context, ref_ptr<dp::Batcher>,
            ref_ptr<dp::TextureManager> textures) const override
  {
    size_t tileFlushes = 0;
    df::UserMarkGenerator generator([&](df::TUserMarksRenderData &&) { ++tileFlushes; });
    auto lines = make_unique_dp<df::UserLinesRenderCollection>();
    auto area = make_unique_dp<df::UserLineRenderParams>();
    area->m_minZoom = 15;
    area->m_fill = make_unique_dp<df::UserAreaFill>();
    area->m_fill->m_bounds = m2::RectD(-0.01, -0.01, 0.01, 0.01);
    area->m_fill->m_textureRect = area->m_fill->m_bounds;
    area->m_fill->m_triangles = {{-0.01, -0.01}, {0.01, -0.01}, {0, 0.01}};
    // Use a stock translucent texture so the regression test needs no automotive assets.
    area->m_fill->m_symbolName = "speedcam-alert-l";
    lines->emplace(1, std::move(area));
    generator.SetUserLines(std::move(lines));
    auto ids = make_unique_dp<df::IDCollections>();
    ids->m_lineIds = {1};
    generator.SetGroup(15, std::move(ids));
    generator.SetGroupVisibility(15, true);

    size_t snapshots = 0;
    generator.GenerateUserAreasGeometry(context, textures, [&](df::TUserMarksRenderData && data)
    {
      ++snapshots;
      TEST_EQUAL(data.size(), 1, ());
      TEST_EQUAL(data.front().m_minZoom, 15, ());
      TEST(data.front().m_bucket != nullptr, ());
    });
    TEST(!generator.AreUserAreasDirty(), ());
    for (int zoom : {15, 16, 17, 16, 15})
    {
      generator.GenerateUserMarksGeometry(context, df::GetTileKeyByPoint({0, 0}, zoom), textures);
      generator.GenerateUserAreasGeometry(context, textures, [&](df::TUserMarksRenderData &&) { ++snapshots; });
    }
    TEST_EQUAL(tileFlushes, 0, ("A fill must not be copied into old/new zoom tiles"));
    TEST_EQUAL(snapshots, 1, ("Zoom reuses the same area snapshot"));

    generator.InvalidateUserAreas();
    generator.GenerateUserAreasGeometry(context, textures, [&](df::TUserMarksRenderData && data)
    {
      ++snapshots;
      TEST_EQUAL(data.size(), 1, ("Style/context recreation recaches the full snapshot"));
    });
    generator.SetGroupVisibility(15, false);
    generator.GenerateUserAreasGeometry(context, textures, [&](df::TUserMarksRenderData && data)
    {
      ++snapshots;
      TEST(data.empty(), ("Disabling the layer explicitly replaces the snapshot with an empty one"));
    });
    TEST_EQUAL(snapshots, 3, ());
  }
};
class SelectableSymbolsTestShape : public df::MapShape
{
public:
  void Draw(ref_ptr<dp::GraphicsContext> context, ref_ptr<dp::Batcher>,
            ref_ptr<dp::TextureManager> textures) const override
  {
    size_t selected = 0;
    df::UserMarkGenerator generator([&](df::TUserMarksRenderData && data)
    {
      for (auto const & item : data)
        item.m_bucket->ForEachOverlay([&](ref_ptr<dp::OverlayHandle> const & handle)
        {
          TEST(handle->IsVisible(), ("A newly cached symbol must be visible before the first tap"));
          TEST(!handle->IndexesRequired(), ("Hit testing must not add per-frame index mutations"));
          ++selected;
        });
    });
    auto marks = make_unique_dp<df::UserMarksRenderCollection>();
    auto ids = make_unique_dp<df::IDCollections>();
    for (uint64_t i = 1; i <= 2; ++i)
    {
      auto mark = make_unique_dp<df::UserMarkRenderParams>();
      mark->m_markId = (kml::kExternalMarkGroupId << 60) | i;
      mark->m_pivot = {1, 1};
      mark->m_minZoom = 13;
      if (i == 1)
      {
        mark->m_symbolNames = make_unique_dp<df::UserPointMark::SymbolNameZoomInfo>();
        mark->m_symbolNames->emplace(13, "speedcam-alert-l");
      }
      else
      {
        mark->m_coloredSymbols = make_unique_dp<df::UserPointMark::ColoredSymbolZoomInfo>();
        df::ColoredSymbolViewParams symbol;
        symbol.m_color = dp::Color::White();
        symbol.m_radiusInPixels = 10;
        mark->m_coloredSymbols->m_zoomInfo.emplace(13, symbol);
      }
      ids->m_markIds.push_back(mark->m_markId);
      auto const id = mark->m_markId;
      marks->emplace(id, std::move(mark));
    }
    generator.SetUserMarks(std::move(marks));
    generator.SetGroup(kml::kExternalMarkGroupId, std::move(ids));
    generator.SetGroupVisibility(kml::kExternalMarkGroupId, true);
    generator.GenerateUserMarksGeometry(context, df::GetTileKeyByPoint({1, 1}, 16), textures);
    TEST_EQUAL(selected, 2, ("Both textured and numeric signs need selection geometry"));
    selected = 0;
    auto coarse = df::GetTileKeyByPoint({1, 1}, 11);
    coarse.m_renderZoom = 16;
    generator.GenerateUserMarksGeometry(context, coarse, textures);
    TEST_EQUAL(selected, 2, ("Cluster terrain LOD must not hide symbols eligible at camera zoom"));
  }
};
}  // namespace

using UserAreaFixture = df::test_support::VisualParamsFixture;

UNIT_CLASS_TEST(UserAreaFixture, UserAreas_NotRebuiltByMapTileLOD)
{
  df::test_support::ShapeTestFixture fixture;
  fixture.Render("Untiled area snapshot", 64, 64,
                 [](auto & shapes) { shapes.AddShape(make_unique_dp<SnapshotTestShape>()); });
}

UNIT_CLASS_TEST(UserAreaFixture, UserMarks_ExternalSymbolsVisibleBeforeFirstTap)
{
  df::test_support::ShapeTestFixture fixture;
  fixture.Render("Selectable external symbols", 64, 64,
                 [](auto & shapes) { shapes.AddShape(make_unique_dp<SelectableSymbolsTestShape>()); });
}
