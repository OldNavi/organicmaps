#pragma once

#include "drape_frontend/external_marks.hpp"
#include "drape_frontend/visual_params.hpp"
#include "geometry/mercator.hpp"
#include "geometry/spatial_hash_grid.hpp"
#include "map/user_mark.hpp"
#include "routing/road_events.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <optional>

class RoadEventLayer : public df::ExternalMarks
{
public:
  explicit RoadEventLayer(std::shared_ptr<routing::RoadEventSource> source) : m_source(std::move(source)) {}
  uint64_t Revision() const override { return m_source->Get().m_revision; }
  kml::MarkGroupId GroupId() const override { return UserMark::EXTERNAL; }

  static std::optional<routing::RoadEvent> Resolve(routing::RoadEventSource const & source, kml::MarkId id)
  {
    auto const state = source.Get();
    auto const index = static_cast<uint32_t>(id);
    // A tap may arrive after an import or a category change, while old geometry is still on screen.
    if (UserMark::GetMarkType(id) != UserMark::EXTERNAL || !state.m_enabled || !state.m_store ||
        (id & kRevisionMask) != (state.m_revision << 32) || index >= state.m_store->Size())
      return {};
    return state.m_store->Get(index);
  }
  RenderData Query(m2::RectD const & rect, int zoom) const override
  {
    RenderData result;
    auto const state = m_source->Get();
    if (!state.m_enabled || !state.m_store)
      return result;
    auto const visualScale = static_cast<float>(df::VisualParams::Instance().GetVisualScale());
    // Use POI-sized symbols at overview zooms; reserve the larger variant for street detail.
    float const symbolSize = zoom >= 17 ? 22.0f : zoom >= 15 ? 18.0f : 14.0f;
    uint32_t hiddenKinds = ~state.m_visibleKinds | (1u << static_cast<unsigned>(routing::RoadEventKind::SettlementEnd));
    for (size_t kind = 0; kind < state.m_minZooms.size(); ++kind)
      if (zoom < state.m_minZooms[kind])
        hiddenKinds |= 1u << kind;
    // The viewport and per-kind zoom bound the layer. A count cap truncates the spatial tree's traversal
    // and leaves dense cities with entire map regions missing, instead of uniformly reducing density.
    double const cameraRadius = (symbolSize + 10.0) * visualScale * df::GetScreenScale(zoom);
    auto indices = SelectCameraSymbols(
        *state.m_store,
        state.m_store->Query(rect, routing::kAllRoadEventCategories, std::numeric_limits<size_t>::max(), hiddenKinds),
        cameraRadius);
    for (auto index : indices)
    {
      auto const & event = state.m_store->Get(index);
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
      auto const id = mark->m_markId;
      result.m_marks->emplace(id, std::move(mark));
    }
    if (zoom >= 15)
    {
      auto areaRect = rect;
      areaRect.Add(mercator::RectByCenterXYAndSizeInMeters(rect.LeftBottom(), 2000));
      areaRect.Add(mercator::RectByCenterXYAndSizeInMeters(rect.RightTop(), 2000));
      constexpr uint32_t cameras = 1u << static_cast<unsigned>(routing::RoadEventCategory::Cameras);
      auto cameraIndices = SelectCameraSymbols(
          *state.m_store, state.m_store->Query(areaRect, cameras, std::numeric_limits<size_t>::max(), hiddenKinds),
          cameraRadius);
      for (auto index : cameraIndices)
      {
        auto const & event = state.m_store->Get(index);
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
    return result;
  }

private:
  static std::vector<size_t> SelectCameraSymbols(routing::RoadEventStore const & store, std::vector<size_t> indices,
                                                 double radius)
  {
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
    std::sort(indices.begin(), indices.end(), [&](size_t a, size_t b)
    {
      auto const & first = store.Get(a);
      auto const & second = store.Get(b);
      int const p = priority(first.m_kind), q = priority(second.m_kind);
      if (p != q)
        return p < q;
      if (first.m_sourceId != second.m_sourceId)
        return first.m_sourceId < second.m_sourceId;
      return a < b;
    });
    // Screen-sized neighborhoods preserve coverage across the whole viewport.
    // Speed cameras are inserted first; opposite or unknown directions must not be merged.
    m2::PointHashMap<size_t> selected(radius);
    std::erase_if(indices, [&](size_t index)
    {
      auto const & event = store.Get(index);
      if (priority(event.m_kind) == 3 || event.m_directionType == 0)
        return false;
      bool duplicate = false;
      selected.ForEachPoint(event.m_position, [&](size_t otherIndex)
      {
        auto const & other = store.Get(otherIndex);
        if (event.m_directionType != other.m_directionType ||
            event.m_position.SquaredLength(other.m_position) > radius * radius)
          return;
        double const period = event.m_directionType == 2 ? 180.0 : 360.0;
        duplicate |= std::abs(std::remainder(static_cast<double>(event.m_direction) - other.m_direction, period)) <= 10;
      });
      if (!duplicate)
        selected.Emplace(event.m_position, index);
      return duplicate;
    });
    return indices;
  }

  static constexpr uint64_t kRevisionMask = 0x0fffffff00000000;
  static kml::MarkId MakeMarkId(uint64_t revision, size_t index)
  {
    CHECK_LESS(revision, uint64_t{1} << 28, ());
    CHECK_LESS(index, uint64_t{1} << 32, ());
    return (static_cast<uint64_t>(UserMark::EXTERNAL) << 60) | (revision << 32) | index;
  }
  std::shared_ptr<routing::RoadEventSource> m_source;
};
