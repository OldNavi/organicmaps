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

RoadEventSource::Snapshot RoadEventSource::Get() const
{
  std::lock_guard lock(m_mutex);
  return m_state;
}
void RoadEventSource::Replace(std::shared_ptr<RoadEventStore const> store)
{
  std::shared_ptr<RoadEventStore const> previous;
  {
    std::lock_guard lock(m_mutex);
    previous = std::move(m_state.m_store);
    m_state.m_store = std::move(store);
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
