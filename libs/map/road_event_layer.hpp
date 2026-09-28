#pragma once

#include "drape_frontend/external_marks.hpp"
#include "drape_frontend/visual_params.hpp"
#include "geometry/mercator.hpp"
#include "geometry/spatial_hash_grid.hpp"
#include "map/mwm_road_events.hpp"
#include "map/routing_mark.hpp"
#include "map/user_mark.hpp"
#include "platform/measurement_utils.hpp"
#include "routing/road_events.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <optional>

class RoadEventLayer : public df::ExternalMarks
{
public:
  static constexpr uint32_t kClusterExcludedKinds = (1u << static_cast<unsigned>(routing::RoadEventKind::Dummy)) |
                                                    (1u << static_cast<unsigned>(routing::RoadEventKind::Video));

  explicit RoadEventLayer(std::shared_ptr<routing::RoadEventSource> source, std::shared_ptr<MwmRoadEvents> maps = {},
                          uint32_t excludedKinds = 0, bool allowSelectionPreview = true)
    : m_source(std::move(source))
    , m_maps(std::move(maps))
    , m_excludedKinds(excludedKinds)
    , m_allowSelectionPreview(allowSelectionPreview)
  {}
  uint64_t Revision() const override { return m_source->Get().m_revision; }
  kml::MarkGroupId GroupId() const override { return UserMark::EXTERNAL; }

  static std::optional<routing::RoadEvent> Resolve(routing::RoadEventSource const & source, kml::MarkId id)
  {
    auto const state = source.Get();
    auto const encodedIndex = static_cast<uint32_t>(id);
    auto const index = encodedIndex & ~kMapCameraBit;
    auto const & store = (encodedIndex & kMapCameraBit) ? state.m_mapCameras : state.m_store;
    // A tap may arrive after an import or a category change, while old geometry is still on screen.
    if (UserMark::GetMarkType(id) != UserMark::EXTERNAL ||
        ((encodedIndex & kMapCameraBit) ? !state.m_useMapCameras : !state.m_enabled) || !store ||
        (id & kRevisionMask) != (state.m_revision << 32) || index >= store->Size())
      return {};
    return store->Get(index);
  }
  RenderData Query(m2::RectD const & viewport, int zoom) const override
  {
    // Renderers may follow a world copy outside [-180, 180]; the index stores canonical longitudes.
    auto rect = mercator::WrapRectX(viewport);
    double constexpr kMinX = mercator::Bounds::kMinX, kMaxX = mercator::Bounds::kMaxX;
    if (rect.SizeX() >= mercator::Bounds::kRangeX)
      return QueryCanonical({kMinX, rect.minY(), kMaxX, rect.maxY()}, zoom);
    if (rect.minX() >= kMinX && rect.maxX() <= kMaxX)
      return QueryCanonical(rect, zoom);

    auto const left = rect.minX() < kMinX;
    auto result =
        QueryCanonical({left ? kMinX : rect.minX(), rect.minY(), left ? rect.maxX() : kMaxX, rect.maxY()}, zoom);
    rect.Offset(left ? mercator::Bounds::kRangeX : -mercator::Bounds::kRangeX, 0);
    auto remainder =
        QueryCanonical({left ? rect.minX() : kMinX, rect.minY(), left ? kMaxX : rect.maxX(), rect.maxY()}, zoom);
    result.m_marks->merge(*remainder.m_marks);
    result.m_lines->merge(*remainder.m_lines);
    return result;
  }

private:
  RenderData QueryCanonical(m2::RectD const & rect, int zoom) const
  {
    RenderData result;
    auto const state = m_source->Get();
    auto const cameraKind = static_cast<size_t>(routing::RoadEventKind::Camera);
    if (state.m_useMapCameras && m_maps && zoom >= state.m_minZooms[cameraKind] &&
        (state.m_visibleKinds & (1u << cameraKind)))
      m_maps->Request(rect);
    if ((!state.m_enabled || !state.m_store) && (!state.m_useMapCameras || !state.m_mapCameras) &&
        !(m_allowSelectionPreview && state.m_coveragePreview))
      return result;
    auto const visualScale = static_cast<float>(df::VisualParams::Instance().GetVisualScale());
    // Use POI-sized symbols at overview zooms; reserve the larger variant for street detail.
    float const symbolSize = zoom >= 17 ? 22.0f : zoom >= 15 ? 18.0f : 14.0f;
    uint32_t hiddenKinds =
        ~state.m_visibleKinds | m_excludedKinds | (1u << static_cast<unsigned>(routing::RoadEventKind::SettlementEnd));
    for (size_t kind = 0; kind < state.m_minZooms.size(); ++kind)
      if (zoom < state.m_minZooms[kind])
        hiddenKinds |= 1u << kind;
    // The viewport and per-kind zoom bound the layer. A count cap truncates the spatial tree's traversal
    // and leaves dense cities with entire map regions missing, instead of uniformly reducing density.
    double const cameraRadius = (symbolSize + 10.0) * visualScale * df::GetScreenScale(zoom);
    auto indices = SelectCameraSymbols(state, rect, routing::kAllRoadEventCategories, hiddenKinds, cameraRadius);
    for (auto const & candidate : indices)
    {
      auto const index = candidate.m_index;
      auto const & event = *candidate.m_event;
      auto mark = make_unique_dp<df::UserMarkRenderParams>();
      mark->m_markId = MakeMarkId(state.m_revision, index);
      mark->m_pivot = event.m_position;
      mark->m_minZoom = mark->m_minTitleZoom = state.m_minZooms[static_cast<size_t>(event.m_kind)];
      mark->m_depthTestEnabled = false;
      mark->m_isSymbolSelectable = true;
      // Paint above map POIs and road labels while keeping their text visible underneath.
      mark->m_depthLayer = mark->m_titleDepthLayer = df::DepthLayer::UserMarkLayer;
      mark->m_priority = static_cast<uint16_t>(UserMark::Priority::RoadWarning);
      mark->m_index = static_cast<uint32_t>(index);
      using Kind = routing::RoadEventKind;
      std::string symbolName;
      switch (event.m_kind)
      {
      case Kind::Dummy: symbolName = "road-event-dummy"; break;
      case Kind::Video: symbolName = "road-event-video"; break;
      case Kind::Bump: symbolName = "road-event-bump"; break;
      case Kind::Crossing: symbolName = "road-event-crossing"; break;
      case Kind::Children: symbolName = "road-event-children"; break;
      case Kind::BadRoad: symbolName = "road-event-rough"; break;
      case Kind::Intersection: symbolName = "road-event-intersection"; break;
      case Kind::Danger: symbolName = "road-event-danger"; break;
      case Kind::Bend: symbolName = "road-event-bend"; break;
      case Kind::SettlementStart: symbolName = "road-event-settlement-start"; break;
      case Kind::NoOvertaking: symbolName = "road-event-no-overtaking"; break;
      case Kind::RedLight: symbolName = "road-event-red-light"; break;
      case Kind::LaneControl: symbolName = "road-event-lane"; break;
      case Kind::Mobile: symbolName = "road-event-mobile"; break;
      case Kind::Railway: symbolName = "road-event-railway"; break;
      case Kind::Police: symbolName = "road-event-police"; break;
      default:
        if (routing::IsCamera(event.m_kind))
          symbolName = "road-event-camera";
        break;
      }
      if (!symbolName.empty())
      {
        mark->m_symbolNames = make_unique_dp<df::UserPointMark::SymbolNameZoomInfo>();
        mark->m_symbolNames->emplace(1, symbolName + "-s");
        mark->m_symbolNames->emplace(15, symbolName + "-m");
        mark->m_symbolNames->emplace(17, symbolName + "-l");
      }
      else
      {
        df::ColoredSymbolViewParams symbol;
        symbol.m_color = dp::Color::White();
        symbol.m_outlineColor = dp::Color(220, 50, 40, 255);
        symbol.m_outlineWidth = visualScale;
        symbol.m_radiusInPixels = 0.5f * symbolSize * visualScale;
        mark->m_coloredSymbols = make_unique_dp<df::UserPointMark::ColoredSymbolZoomInfo>();
        mark->m_coloredSymbols->m_zoomInfo.emplace(1, symbol);
      }
      dp::TitleDecl title;
      title.m_primaryTextFont.m_color = dp::Color::Black();
      title.m_primaryTextFont.m_outlineColor = dp::Color::White();
      title.m_primaryTextFont.m_size = 0.43f * symbolSize;
      title.m_anchor = dp::Center;
      switch (event.m_kind)
      {
      case Kind::SpeedLimit: title.m_primaryText = std::to_string(event.m_speedKmh); break;
      default: break;
      }
      if (!title.m_primaryText.empty())
      {
        mark->m_titleDecl = make_unique_dp<df::UserPointMark::TitlesInfo>();
        mark->m_titleDecl->push_back(std::move(title));
        mark->m_hasTitlePriority = true;
      }
      if (routing::IsSpeedCamera(event.m_kind) && event.m_speedKmh > 0)
      {
        mark->m_titleDecl = make_unique_dp<df::UserPointMark::TitlesInfo>(1);
        mark->m_coloredSymbols = make_unique_dp<df::UserPointMark::ColoredSymbolZoomInfo>();
        auto & badge = mark->m_titleDecl->front();
        SpeedCameraMark::ConfigureBadge(badge, *mark->m_coloredSymbols, mark->m_minTitleZoom);
        double speed = event.m_speedKmh;
        if (measurement_utils::GetMeasurementUnits() == measurement_utils::Units::Imperial)
          speed = measurement_utils::KmphToMiph(speed);
        badge.m_primaryText = std::to_string(static_cast<int>(speed + 0.5));
        mark->m_hasTitlePriority = true;
      }
      auto const id = mark->m_markId;
      result.m_marks->emplace(id, std::move(mark));
    }
    if (state.m_coverageVisible && zoom >= 15)
    {
      auto areaRect = rect;
      areaRect.Add(mercator::RectByCenterXYAndSizeInMeters(rect.LeftBottom(), 2000));
      areaRect.Add(mercator::RectByCenterXYAndSizeInMeters(rect.RightTop(), 2000));
      constexpr uint32_t cameras = 1u << static_cast<unsigned>(routing::RoadEventCategory::Cameras);
      auto cameraIndices = SelectCameraSymbols(state, areaRect, cameras, hiddenKinds, cameraRadius);
      for (auto const & candidate : cameraIndices)
      {
        auto const index = candidate.m_index;
        if (state.m_filterCoverage)
        {
          if (!state.m_coverage)
            continue;
          auto const & allowed = (index & kMapCameraBit) ? state.m_coverage->m_map : state.m_coverage->m_imported;
          if (!std::binary_search(allowed.begin(), allowed.end(), index & ~kMapCameraBit))
            continue;
        }
        auto const & event = *candidate.m_event;
        if (m_allowSelectionPreview && state.m_coveragePreview &&
            event.m_sourceId == state.m_coveragePreview->m_sourceId &&
            event.m_position == state.m_coveragePreview->m_position)
          continue;
        auto const id = MakeMarkId(state.m_revision, index);
        if (rect.IsPointInside(event.m_position) && !result.m_marks->contains(id))
          continue;
        auto area = routing::BuildCameraApproachArea(event);
        if (area.m_triangles.empty() || !area.m_bounds.IsIntersect(rect))
          continue;
        auto line = make_unique_dp<df::UserLineRenderParams>();
        line->m_minZoom = 15;
        line->m_fill = make_unique_dp<df::UserAreaFill>();
        line->m_fill->m_triangles = std::move(area.m_triangles);
        line->m_fill->m_bounds = area.m_bounds;
        line->m_fill->m_textureRect = area.m_textureRect;
        line->m_fill->m_symbolName = "road-event-coverage";
        result.m_lines->emplace(id, std::move(line));
      }
    }
    if (m_allowSelectionPreview && state.m_coveragePreview)
    {
      auto area = routing::BuildCameraApproachArea(*state.m_coveragePreview);
      if (!area.m_triangles.empty() && area.m_bounds.IsIntersect(rect))
      {
        auto line = make_unique_dp<df::UserLineRenderParams>();
        line->m_minZoom = 1;
        line->m_fill = make_unique_dp<df::UserAreaFill>();
        line->m_fill->m_triangles = std::move(area.m_triangles);
        line->m_fill->m_bounds = area.m_bounds;
        line->m_fill->m_textureRect = area.m_textureRect;
        line->m_fill->m_symbolName = "road-event-coverage";
        result.m_lines->emplace(MakeMarkId(state.m_revision, std::numeric_limits<uint32_t>::max()), std::move(line));
      }
    }
    return result;
  }

  struct Candidate
  {
    routing::RoadEvent const * m_event;
    uint32_t m_index;
  };
  static std::vector<Candidate> SelectCameraSymbols(routing::RoadEventSource::Snapshot const & state,
                                                    m2::RectD const & rect, uint32_t categories, uint32_t hiddenKinds,
                                                    double radius)
  {
    std::vector<Candidate> indices;
    auto append = [&](std::shared_ptr<routing::RoadEventStore const> const & store, uint32_t bit)
    {
      if (!store)
        return;
      for (auto index : store->Query(rect, categories, std::numeric_limits<size_t>::max(), hiddenKinds))
      {
        CHECK_LESS(index, kMapCameraBit, ());
        indices.push_back({&store->Get(index), static_cast<uint32_t>(index) | bit});
      }
    };
    if (state.m_enabled)
      append(state.m_store, 0);
    if (state.m_useMapCameras)
      append(state.m_mapCameras, kMapCameraBit);
    auto const priority = [](routing::RoadEventKind kind)
    {
      if (routing::IsSpeedCamera(kind))
        return 0;
      if (kind == routing::RoadEventKind::Dummy)
        return 2;
      if (routing::IsCamera(kind) && kind != routing::RoadEventKind::Police)
        return 1;
      return 3;
    };
    std::sort(indices.begin(), indices.end(), [&](Candidate const & a, Candidate const & b)
    {
      auto const & first = *a.m_event;
      auto const & second = *b.m_event;
      if ((a.m_index & kMapCameraBit) != (b.m_index & kMapCameraBit))
        return (a.m_index & kMapCameraBit) == 0;
      int const p = priority(first.m_kind), q = priority(second.m_kind);
      if (p != q)
        return p < q;
      if (first.m_sourceId != second.m_sourceId)
        return first.m_sourceId < second.m_sourceId;
      return a.m_index < b.m_index;
    });
    // Screen-sized neighborhoods preserve coverage across the whole viewport.
    // Speed cameras are inserted first; opposite or unknown directions must not be merged.
    // Cross-source duplicates use a fixed 50 m neighborhood even at street zoom.
    double const mergeRadius = std::max(
        radius, mercator::GetSmPoint(rect.Center(), MwmRoadEvents::kDuplicateDistanceMeters, 0).x - rect.Center().x);
    m2::PointHashMap<Candidate> selected(mergeRadius);
    std::erase_if(indices, [&](Candidate const & candidate)
    {
      auto const & event = *candidate.m_event;
      if (priority(event.m_kind) == 3 || event.m_directionType == 0)
        return false;
      bool duplicate = false;
      selected.ForEachPoint(event.m_position, [&](Candidate const & otherCandidate)
      {
        auto const & other = *otherCandidate.m_event;
        bool const crossSource = (candidate.m_index & kMapCameraBit) != (otherCandidate.m_index & kMapCameraBit);
        if (crossSource)
        {
          if (!routing::IsSpeedCamera(other.m_kind) ||
              mercator::DistanceOnEarth(event.m_position, other.m_position) > MwmRoadEvents::kDuplicateDistanceMeters)
            return;
        }
        else if (event.m_directionType != other.m_directionType ||
                 event.m_position.SquaredLength(other.m_position) > radius * radius)
          return;
        double const period = event.m_directionType == 2 || (crossSource && other.m_directionType == 2) ? 180.0 : 360.0;
        duplicate |= std::abs(std::remainder(static_cast<double>(event.m_direction) - other.m_direction, period)) <= 10;
      });
      if (!duplicate)
        selected.Emplace(event.m_position, candidate);
      return duplicate;
    });
    return indices;
  }

  static constexpr uint32_t kMapCameraBit = uint32_t{1} << 31;
  static constexpr uint64_t kRevisionMask = 0x0fffffff00000000;
  static kml::MarkId MakeMarkId(uint64_t revision, size_t index)
  {
    CHECK_LESS(revision, uint64_t{1} << 28, ());
    CHECK_LESS(index, uint64_t{1} << 32, ());
    return (static_cast<uint64_t>(UserMark::EXTERNAL) << 60) | (revision << 32) | index;
  }
  std::shared_ptr<routing::RoadEventSource> m_source;
  std::shared_ptr<MwmRoadEvents> m_maps;
  uint32_t const m_excludedKinds;
  bool const m_allowSelectionPreview;
};
