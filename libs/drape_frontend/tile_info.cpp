#include "drape_frontend/tile_info.hpp"
#include "drape_frontend/engine_context.hpp"
#include "drape_frontend/map_data_provider.hpp"
#include "drape_frontend/metaline_manager.hpp"
#include "drape_frontend/rule_drawer.hpp"
#include "drape_frontend/tile_utils.hpp"

#include "base/scope_guard.hpp"

#include <algorithm>
#ifdef OMIM_AUTO
#include <map>
#include "drape_frontend/road_detail_geometry.hpp"
#include "drape_frontend/road_junction_geometry.hpp"
#include "drape_frontend/road_label_occlusion.hpp"
#include "geometry/mercator.hpp"
#include "indexer/classificator.hpp"
#include "indexer/feature.hpp"
#endif

namespace df
{
TileInfo::TileInfo(drape_ptr<EngineContext> && engineContext) : m_context(std::move(engineContext)), m_isCanceled(false)
{}

void TileInfo::ReadFeatureIndex(MapDataProvider const & model)
{
  if (!DoNeedReadIndex())
    return;

  ThrowIfCancelled();

  size_t const kAverageFeaturesCount = 256;
  m_featureInfo.reserve(kAverageFeaturesCount);

  MwmSet::MwmId lastMwm;
  model.ReadFeaturesID([this, &lastMwm](FeatureID const & id)
  {
    if (m_mwms.empty() || lastMwm != id.m_mwmId)
    {
      auto result = m_mwms.insert(id.m_mwmId);
      VERIFY(result.second, ());
      lastMwm = id.m_mwmId;
    }
    m_featureInfo.push_back(id);
  }, GetTileKey().GetWrappedDataRect(), GetZoomLevel());
}

void TileInfo::ReadFeatures(MapDataProvider const & model)
{
#if defined(DRAPE_MEASURER_BENCHMARK) && defined(TILES_STATISTIC)
  DrapeMeasurer::Instance().StartTileReading();
#endif
  m_context->BeginReadTile();

  // Reading can be interrupted by exception throwing
  SCOPE_GUARD(ReleaseReadTile, [this] { m_context->EndReadTile(IsCancelled()); });

  ReadFeatureIndex(model);
  ThrowIfCancelled();

  m_context->GetMetalineManager()->Update(m_mwms);

  if (!m_featureInfo.empty())
  {
    std::sort(m_featureInfo.begin(), m_featureInfo.end());

#ifdef OMIM_AUTO
    std::map<FeatureID, std::shared_ptr<RoadDetailGeometry const>> roadGeometry;
#endif
    RuleDrawer drawer(std::bind(&TileInfo::IsCancelled, this), model.m_isCountryLoadedByName, make_ref(m_context),
                      m_context->GetMapLangIndex());
#ifdef OMIM_AUTO
    if (GetTileKey().m_zoomLevel >= 17)
    {
      auto labels = std::make_shared<RoadLabelOcclusion>();
      auto decks = std::make_shared<RoadDecks>();
      std::set<feature::RoadJunction const *> labelJunctions;
      auto const bridgeDeckType = classif().GetTypeByPath({"man_made", "bridge"});
      ApplyFeatureParams labelParams;
      labelParams.Init(GetTileKey());
      ftypes::IsBridgeOrTunnelChecker const bridgeOrTunnel;
      model.ReadFeatures([&](FeatureType & feature)
      {
        ThrowIfCancelled();
        auto const details = feature.GetRoadDetails();
        int const layer = feature.GetLayer() == feature::LAYER_EMPTY ? 0 : feature.GetLayer();
        if (feature.GetGeomType() == feature::GeomType::Area && feature::TypesHolder(feature).Has(bridgeDeckType))
        {
          std::vector<m2::PointD> triangles;
          feature.ForEachTriangle([&](auto const & a, auto const & b, auto const & c)
          { triangles.insert(triangles.end(), {a, b, c}); }, FeatureType::BEST_GEOMETRY);
          decks->Add(layer, std::move(triangles));
          return;
        }
        if (!details && (feature.GetGeomType() != feature::GeomType::Line || layer <= 0 || !bridgeOrTunnel(feature)))
          return;
        std::vector<m2::PointD> path;
        feature.ForEachPoint([&](auto const & point) { path.push_back(point); }, FeatureType::BEST_GEOMETRY);
        auto const junctions = feature.GetRoadJunctions();
        auto geometry = std::make_shared<RoadDetailGeometry>(path, junctions, details && details->m_roundabout,
                                                             details ? details->m_startOffsetCm * 0.01 : 0,
                                                             details ? details->m_endOffsetCm * 0.01 : 0);
        double width = details ? details->WidthMeters() : 0;
        if (!details && geometry->IsValid())
        {
          // Older maps and pedestrian bridges still use style widths in pixels.
          Stylist style(feature, GetTileKey().GetRenderZoom(), m_context->GetMapLangIndex(), false);
          for (auto const * rule : style.m_lineRules)
            if (!rule->pathsym.has_value())
              width = std::max(width, static_cast<double>(rule->width));
          double const metersPerUnit =
              mercator::DistanceOnEarth(path.front(), path.front() + m2::PointD(0.00001, 0)) / 0.00001;
          width *= labelParams.m_vparams.GetVisualScale() * metersPerUnit / labelParams.m_currentScaleGtoP;
        }
        roadGeometry.emplace(feature.GetID(), geometry);
        labels->Add(layer, width, std::move(geometry));
        for (auto const & link : junctions)
          if (link.m_junction->m_ownerFeatureId == feature.GetID().m_index &&
              labelJunctions.insert(link.m_junction).second)
            labels->AddJunction(layer, *link.m_junction);
      }, m_featureInfo);
      drawer.SetRoadLabelOcclusion(std::move(labels));
      drawer.SetRoadDecks(std::move(decks));
    }
    drawer.SetRoadGeometryGetter([&](FeatureID const & id) -> std::shared_ptr<RoadDetailGeometry const>
    {
      ThrowIfCancelled();
      if (auto const it = roadGeometry.find(id); it != roadGeometry.end())
        return it->second;
      std::shared_ptr<RoadDetailGeometry const> geometry;
      model.ReadFeatures([&](FeatureType & feature)
      {
        if (auto const details = feature.GetRoadDetails())
        {
          std::vector<m2::PointD> path;
          feature.ForEachPoint([&](auto const & point) { path.push_back(point); }, FeatureType::BEST_GEOMETRY);
          geometry =
              std::make_shared<RoadDetailGeometry>(path, feature.GetRoadJunctions(), details->m_roundabout,
                                                   details->m_startOffsetCm * 0.01, details->m_endOffsetCm * 0.01);
        }
      }, {id});
      roadGeometry.emplace(id, geometry);
      return geometry;
    });
#endif
    model.ReadFeatures([&drawer](FeatureType & ft) { drawer(ft); }, m_featureInfo);
#ifdef DRAW_TILE_NET
    drawer.DrawTileNet();
#endif
  }
#if defined(DRAPE_MEASURER_BENCHMARK) && defined(TILES_STATISTIC)
  DrapeMeasurer::Instance().EndTileReading();
#endif
}

void TileInfo::Cancel()
{
  m_isCanceled = true;
}

/*
 * TODO: the following check throws an exception while IsCancelled() is used in most places to quit gracefully.
 * Looks like the latter was added later, so maybe the throwing version is not needed anymore.
 */
void TileInfo::ThrowIfCancelled() const
{
  // The exception is handled in ReadMWMTask::Do().
  if (m_isCanceled)
    MYTHROW(ReadCanceledException, ());
}

bool TileInfo::IsCancelled() const
{
  return m_isCanceled;
}

bool TileInfo::DoNeedReadIndex() const
{
  return m_featureInfo.empty();
}

int TileInfo::GetZoomLevel() const
{
  return ClipTileZoomByMaxDataZoom(m_context->GetTileKey().m_zoomLevel);
}
}  // namespace df
