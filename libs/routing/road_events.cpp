#include "routing/road_events.hpp"

#include "base/math.hpp"
#include "geometry/mercator.hpp"

#include <algorithm>

#include <cmath>

namespace routing
{

RoadEventCategory GetCategory(RoadEventKind kind)
{
  switch (kind)
  {
  case RoadEventKind::AverageStart:
  case RoadEventKind::AverageEnd: return RoadEventCategory::AverageSpeed;
  case RoadEventKind::SpeedLimit: return RoadEventCategory::SpeedLimits;
  case RoadEventKind::SettlementStart:
  case RoadEventKind::SettlementEnd: return RoadEventCategory::Settlements;
  case RoadEventKind::Bump: return RoadEventCategory::Bumps;
  case RoadEventKind::Crossing:
  case RoadEventKind::Children: return RoadEventCategory::Crossings;
  case RoadEventKind::Railway: return RoadEventCategory::Railways;
  case RoadEventKind::BadRoad:
  case RoadEventKind::Bend:
  case RoadEventKind::Intersection:
  case RoadEventKind::Danger: return RoadEventCategory::Hazards;
  case RoadEventKind::NoOvertaking: return RoadEventCategory::NoOvertaking;
  default: return RoadEventCategory::Cameras;
  }
}

bool IsCamera(RoadEventKind kind)
{
  return GetCategory(kind) == RoadEventCategory::Cameras || GetCategory(kind) == RoadEventCategory::AverageSpeed;
}

bool IsSpeedCamera(RoadEventKind kind)
{
  switch (kind)
  {
  case RoadEventKind::Camera:
  case RoadEventKind::Mobile:
  case RoadEventKind::AverageStart:
  case RoadEventKind::AverageEnd: return true;
  default: return false;
  }
}

bool RoadEvent::MatchesBearing(double travelBearing) const
{
  if (!std::isfinite(travelBearing))
    return false;
  if (m_directionType == 0)
    return true;
  double const difference = std::abs(std::remainder(travelBearing - m_direction, 360.0));
  double const tolerance = m_angle;
  return difference <= tolerance || (m_directionType == 2 && 180.0 - difference <= tolerance);
}

bool RoadEvent::IsInApproachSector(m2::PointD const & position) const
{
  if (m_distance == 0 || mercator::DistanceOnEarth(position, m_position) > m_distance)
    return false;
  auto const direction = m_position - position;
  return MatchesBearing(math::RadToDeg(std::atan2(direction.x, direction.y)));
}

CameraApproachArea BuildCameraApproachArea(RoadEvent const & event)
{
  CameraApproachArea result;
  using Kind = RoadEventKind;
  if (event.m_kind != Kind::Camera && event.m_kind != Kind::Mobile && event.m_kind != Kind::RedLight &&
      event.m_kind != Kind::LaneControl)
    return result;
  // An unspecified direction/angle does not establish a cone; do not invent one.
  if (event.m_directionType == 0 || event.m_angle == 0 || event.m_distance == 0)
    return result;

  double const radius = std::min<double>(event.m_distance, 2000.0);
  auto const east = mercator::GetSmPoint(event.m_position, radius, 0);
  double const extent = east.x - event.m_position.x;
  result.m_textureRect = mercator::RectByCenterXYAndOffset(event.m_position, extent);
  auto const pointAt = [&](double bearing)
  {
    double const angle = math::DegToRad(bearing);
    return event.m_position + m2::PointD(std::sin(angle), std::cos(angle)) * extent;
  };
  int const steps = std::max(2, static_cast<int>(std::ceil(event.m_angle / 3.0)));
  auto const addCone = [&](double direction)
  {
    for (int step = 0; step < steps; ++step)
    {
      double const start = direction - event.m_angle + 2.0 * event.m_angle * step / steps;
      double const end = direction - event.m_angle + 2.0 * event.m_angle * (step + 1) / steps;
      result.m_triangles.insert(result.m_triangles.end(), {event.m_position, pointAt(start), pointAt(end)});
    }
  };
  // Normalized direction follows the vehicle; its approach lies behind the camera.
  addCone(event.m_direction + 180.0);
  if (event.m_directionType == 2)
    addCone(event.m_direction);
  for (auto const & point : result.m_triangles)
    result.m_bounds.Add(point);
  return result;
}

void RoadEventStore::Add(RoadEvent && event)
{
  auto const p = event.m_position;
  m_index.Add(m_events.size(), m2::RectD(p.x, p.y, p.x, p.y));
  m_events.push_back(std::move(event));
}

std::vector<size_t> RoadEventStore::Query(m2::RectD const & rect, uint32_t categories, size_t limit,
                                          uint32_t excludedKinds) const
{
  std::vector<size_t> result;
  if (categories == 0 || limit == 0)
    return result;
  m_index.ForEachInRect(rect, [&](size_t index)
  {
    auto const kind = m_events[index].m_kind;
    if (result.size() < limit && !(excludedKinds & (1u << static_cast<unsigned>(kind))) &&
        (categories & (1u << static_cast<unsigned>(GetCategory(kind)))))
      result.push_back(index);
  });
  return result;
}

bool RoadEvent::MatchesRoadBearing(double travelBearing) const
{
  if (!IsSpeedCamera(m_kind) && m_kind != RoadEventKind::RedLight && m_kind != RoadEventKind::LaneControl)
    return MatchesBearing(travelBearing);
  if (m_directionType == 0)
    return true;
  double const period = m_directionType == 2 ? 180.0 : 360.0;
  // FOV constrains the vehicle's position, not the road tangent. Reject traffic moving away from the sector.
  return std::abs(std::remainder(travelBearing - m_direction, period)) < 90.0;
}

double RoadEventMatchRadius(RoadEvent const & event)
{
  if (!event.m_directionType || !event.m_distance || !event.m_angle ||
      (!IsSpeedCamera(event.m_kind) && event.m_kind != RoadEventKind::RedLight &&
       event.m_kind != RoadEventKind::LaneControl))
    return kRoadEventRoadDistanceMeters;
  // Source coordinates may describe a pole or building beside the controlled road.
  // Bound association by the sector's lateral extent; the actual warning still requires entering that sector.
  double const lateral =
      std::min<double>(2000, event.m_distance) * std::sin(math::DegToRad(std::min<double>(90, event.m_angle)));
  return std::clamp(lateral, kRoadEventRoadDistanceMeters, kMaxCameraRoadDistanceMeters);
}

RoadEventSource::Snapshot RoadEventSource::Get() const
{
  std::lock_guard lock(m_mutex);
  return m_state;
}
bool RoadEventSource::SetCoverageVisible(bool visible)
{
  std::lock_guard lock(m_mutex);
  if (m_state.m_coverageVisible == visible)
    return false;
  m_state.m_coverageVisible = visible;
  ++m_state.m_revision;
  return true;
}
void RoadEventSource::EnableCoverageFilter()
{
  std::lock_guard lock(m_mutex);
  if (!m_state.m_filterCoverage)
  {
    m_state.m_filterCoverage = true;
    ++m_state.m_revision;
  }
}
void RoadEventSource::SetCoverageRoute(std::shared_ptr<CameraCoveragePath const> route)
{
  std::lock_guard lock(m_mutex);
  if (m_state.m_coverageRoute == route)
    return;
  m_state.m_coverageRoute = std::move(route);
  m_state.m_coverage.reset();
  ++m_state.m_coverageGeneration;
  ++m_state.m_revision;
}
bool RoadEventSource::ClearCoverage()
{
  std::lock_guard lock(m_mutex);
  ++m_state.m_coverageGeneration;
  if (!m_state.m_coverage)
    return false;
  m_state.m_coverage.reset();
  ++m_state.m_revision;
  return true;
}
bool RoadEventSource::SetCoverage(Snapshot const & input, Coverage coverage)
{
  std::sort(coverage.m_imported.begin(), coverage.m_imported.end());
  std::sort(coverage.m_map.begin(), coverage.m_map.end());
  std::lock_guard lock(m_mutex);
  // A location worker may finish after a reroute, map replacement or GPS expiry.
  if (input.m_coverageGeneration != m_state.m_coverageGeneration || input.m_store != m_state.m_store ||
      input.m_mapCameras != m_state.m_mapCameras)
    return false;
  if (m_state.m_coverage && *m_state.m_coverage == coverage)
    return false;
  m_state.m_coverage = std::make_shared<Coverage const>(std::move(coverage));
  ++m_state.m_revision;
  return true;
}
bool RoadEventSource::SetCoveragePreview(std::optional<RoadEvent> const & event)
{
  std::lock_guard lock(m_mutex);
  if ((!event && !m_state.m_coveragePreview) ||
      (event && m_state.m_coveragePreview && *event == *m_state.m_coveragePreview))
    return false;
  m_state.m_coveragePreview = event ? std::make_shared<RoadEvent const>(*event) : nullptr;
  ++m_state.m_revision;
  return true;
}

void RoadEventSource::Replace(std::shared_ptr<RoadEventStore const> store)
{
  std::shared_ptr<RoadEventStore const> previous;
  {
    std::lock_guard lock(m_mutex);
    previous = std::move(m_state.m_store);
    m_state.m_store = std::move(store);
    m_state.m_coverage.reset();
    ++m_state.m_revision;
  }
  // Releasing a country's spatial index must not hold up renderer snapshots.
}
void RoadEventSource::Configure(bool enabled, bool warnings, uint32_t visibleKinds, RoadEventMinZooms const & minZooms)
{
  std::lock_guard lock(m_mutex);
  visibleKinds &= kAllRoadEventKinds;
  if (m_state.m_enabled == enabled && m_state.m_warnings == warnings && m_state.m_visibleKinds == visibleKinds &&
      m_state.m_minZooms == minZooms)
    return;
  m_state.m_enabled = enabled;
  m_state.m_warnings = warnings;
  m_state.m_visibleKinds = visibleKinds & kAllRoadEventKinds;
  m_state.m_minZooms = minZooms;
  ++m_state.m_revision;
}

void RoadEventSource::ReplaceMapCameras(std::shared_ptr<RoadEventStore const> store)
{
  std::lock_guard lock(m_mutex);
  m_state.m_mapCameras = std::move(store);
  m_state.m_coverage.reset();
  ++m_state.m_revision;
}

void RoadEventSource::EnableMapCameras()
{
  std::lock_guard lock(m_mutex);
  if (!m_state.m_useMapCameras)
  {
    m_state.m_useMapCameras = true;
    ++m_state.m_revision;
  }
}
void RoadEventSource::InvalidateMapCameras()
{
  std::lock_guard lock(m_mutex);
  ++m_state.m_revision;
}
}  // namespace routing
