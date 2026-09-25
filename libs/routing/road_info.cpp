#include "routing/road_info.hpp"
#include "routing/camera_coverage.hpp"

#include "routing/index_graph_loader.hpp"
#include "routing/speed_camera_prohibition.hpp"
#include "routing_common/car_model.hpp"

#include "base/math.hpp"
#include "base/scope_guard.hpp"
#include "geometry/mercator.hpp"
#include "indexer/feature.hpp"

#include <algorithm>
#include <cmath>
#include <set>

namespace routing
{
#ifdef OMIM_AUTO
bool AreConsecutiveRoadEdges(Edge const & a, Edge const & b)
{
  if (a.IsFake() || b.IsFake() || (a.GetEndPoint() != b.GetStartPoint() && b.GetEndPoint() != a.GetStartPoint()))
    return false;
  auto const da = a.GetDirection(), db = b.GetDirection();
  if (da.IsAlmostZero() || db.IsAlmostZero())
    return false;
  // Adjacent near-straight fragments describe one approach, not competing parallel roads.
  return DotProduct(da, db) >= std::cos(math::DegToRad(15.0)) * da.Length() * db.Length();
}

std::optional<double> ProjectRoadEvent(m2::PointD const & position, Edge const & edge, double maximumDistance)
{
  auto const direction = edge.GetDirection();
  if (direction.IsAlmostZero())
    return {};
  double const rawCoefficient = DotProduct(position - edge.GetStartPoint(), direction) / direction.SquaredLength();
  double const coefficient = std::clamp(rawCoefficient, 0.0, 1.0);
  if (mercator::DistanceOnEarth(edge.GetStartPoint() + direction * coefficient,
                                edge.GetStartPoint() + direction * rawCoefficient) > kRoadEventRoadDistanceMeters)
    return {};
  if (mercator::DistanceOnEarth(edge.GetStartPoint() + direction * coefficient, position) > maximumDistance)
    return {};
  return coefficient;
}
#endif

std::optional<IRoadGraph::EdgeProjectionT> MatchRoad(m2::PointD const & position, m2::PointD const & direction,
                                                     double accuracy,
                                                     std::vector<IRoadGraph::EdgeProjectionT> const & candidates,
                                                     double maximumDistance, bool allowJunctionOverlap)
{
  if (direction.IsAlmostZero() || !std::isfinite(accuracy) || accuracy <= 0 || accuracy > 30)
    return {};
  struct Candidate
  {
    double m_score;
    IRoadGraph::EdgeProjectionT const * m_projection;
  };
  std::vector<Candidate> ranked;
  for (auto const & candidate : candidates)
  {
    auto const & edge = candidate.first;
    auto const d = edge.GetDirection();
    if (edge.IsFake() || d.IsAlmostZero())
      continue;
    double const cosine = DotProduct(d, direction) / (d.Length() * direction.Length());
    double const distance = mercator::DistanceOnEarth(position, candidate.second.GetPoint());
    double const tolerance = maximumDistance > 0 ? maximumDistance : std::clamp(accuracy * 2.0, 10.0, 40.0);
    if (cosine < 0.7 || distance > tolerance)
      continue;
    ranked.push_back({distance + 5.0 * (1.0 - cosine), &candidate});
  }
  if (ranked.empty())
    return {};
  std::sort(ranked.begin(), ranked.end(), [](auto const & a, auto const & b) { return a.m_score < b.m_score; });
  auto const & best = *ranked.front().m_projection;
  for (size_t i = 1; i < ranked.size(); ++i)
  {
    auto const & edge = ranked[i].m_projection->first;
    if (edge.GetFeatureId() == best.first.GetFeatureId() && edge.IsForward() == best.first.IsForward() &&
        std::abs(static_cast<int64_t>(edge.GetSegId()) - best.first.GetSegId()) <= 1)
      continue;
#ifdef OMIM_AUTO
    if (AreConsecutiveRoadEdges(edge, best.first))
      continue;
    if (allowJunctionOverlap)
    {
      std::optional<m2::PointD> junction;
      if (edge.GetStartPoint() == best.first.GetStartPoint())
        junction = edge.GetStartPoint();
      else if (edge.GetEndPoint() == best.first.GetEndPoint())
        junction = edge.GetEndPoint();
      // Near a fork/merge the camera may cover the common approach of both fragments.
      // This exception applies to camera association, never to vehicle GPS matching.
      if (junction && mercator::DistanceOnEarth(*junction, best.second.GetPoint()) <= kRoadEventRoadDistanceMeters &&
          mercator::DistanceOnEarth(*junction, ranked[i].m_projection->second.GetPoint()) <=
              kRoadEventRoadDistanceMeters)
        continue;
    }
#endif
    if (ranked[i].m_score - ranked.front().m_score < std::max(3.0, accuracy * 0.5))
      return {};
  }
  return best;
}

void FindRoadCamera(IRoadGraph const & graph, Edge edge, m2::PointD const & position, RoadCameraGetter const & cameras,
                    RoadInfoSnapshot & result)
{
  double const kHorizonMeters = 2000.0;
  double passed = -mercator::DistanceOnEarth(edge.GetStartPoint(), position);
  std::set<Edge> visited;
  for (size_t n = 0; n < 256 && passed < kHorizonMeters && visited.insert(edge).second; ++n)
  {
    double const length = mercator::DistanceOnEarth(edge.GetStartPoint(), edge.GetEndPoint());
    for (auto const & camera : cameras(edge))
    {
      double const coefficient = edge.IsForward() ? camera.m_coef : 1.0 - camera.m_coef;
      double const distance = passed + length * coefficient;
      if (distance < 0.0 || distance > kHorizonMeters ||
          (result.m_cameraDistance >= 0.0 && distance >= result.m_cameraDistance))
        continue;
      result.m_cameraDistance = distance;
      result.m_cameraLimitMps =
          camera.m_maxSpeedKmPH == SpeedCameraOnRoute::kNoSpeedInfo ? 0.0 : camera.m_maxSpeedKmPH / 3.6;
      result.m_cameraPosition = edge.GetStartPoint() + edge.GetDirection() * coefficient;
    }
    if (result.m_cameraDistance >= 0.0)
      return;
    passed += length;
    IRoadGraph::EdgeListT outgoing;
    graph.GetOutgoingEdges(edge.GetEndJunction(), outgoing);
    std::set<Edge> choices;
    for (auto const & next : outgoing)
      if (!next.IsFake() && next != edge.GetReverseEdge())
        choices.insert(next);
    // No route: do not guess the driver's choice at a junction.
    if (choices.size() != 1)
      return;
    edge = *choices.begin();
  }
}

RoadInfoReader::RoadInfoReader(DataSource & source, VehicleModelFactory::CountryParentNameGetterFn const & parents,
                               std::shared_ptr<RoadEventSource> events)
  : m_events(std::move(events))
  , m_source(source, nullptr)
  , m_graph(m_source, IRoadGraph::Mode::ObeyOnewayTag, std::make_shared<CarModelFactory>(parents))
{}

RoadInfoReader::Attributes & RoadInfoReader::GetAttributes(MwmSet::MwmId const & id)
{
  auto [it, inserted] = m_attributes.try_emplace(id);
  if (inserted)
  {
    auto const & handle = m_source.GetHandle(id);
    it->second.m_speeds = LoadMaxspeeds(handle);
    if (!AreSpeedCamerasProhibited(handle.GetInfo()->GetLocalFile().GetCountryFile()))
      ReadSpeedCamsFromMwm(*handle.GetValue(), it->second.m_cameras);
  }
  return it->second;
}

RoadInfoSnapshot RoadInfoReader::Read(location::GpsInfo const & location)
{
  // Do not pin map files across updates/removals. Attribute caches are keyed by MwmId.
  SCOPE_GUARD(release, [this] { m_source.FreeHandles(); });
  RoadInfoSnapshot result;
  double const time = location.m_timestamp;
  if (!std::isfinite(time) || !std::isfinite(location.m_latitude) || !std::isfinite(location.m_longitude) ||
      std::abs(location.m_latitude) > 85.0 || std::abs(location.m_longitude) > 180.0)
  {
#ifdef OMIM_AUTO
    if (m_events)
      result.m_coverageChanged = m_events->ClearCoverage();
#endif
    return result;
  }
  auto const position = mercator::FromLatLon(location.m_latitude, location.m_longitude);
  bool const continuous =
      m_previousLocation && time > m_previousLocation->m_timestamp && time - m_previousLocation->m_timestamp <= 5.0 &&
      mercator::DistanceOnEarth(
          position, mercator::FromLatLon(m_previousLocation->m_latitude, m_previousLocation->m_longitude)) < 200.0;
  if (!continuous)
  {
    m_previousMatch.reset();
    m_direction = {};
  }
  if (location.HasBearing() && std::isfinite(location.m_bearing) && location.m_speed >= 1.0)
  {
    double const angle = math::DegToRad(location.m_bearing);
    m_direction = {std::sin(angle), std::cos(angle)};
    m_directionTime = time;
  }
  else if (continuous)
  {
    auto const previous = mercator::FromLatLon(m_previousLocation->m_latitude, m_previousLocation->m_longitude);
    if (mercator::DistanceOnEarth(position, previous) >= std::max(5.0, location.m_horizontalAccuracy))
    {
      m_direction = position - previous;
      m_directionTime = time;
    }
    else if (m_previousMatch && location.m_speed >= 0.0 && location.m_speed < 1.0)
    {
      // A fresh stationary fix does not invalidate the approach direction at a traffic light.
      m_directionTime = time;
    }
  }
  auto const previousPosition =
      m_previousLocation ? mercator::FromLatLon(m_previousLocation->m_latitude, m_previousLocation->m_longitude)
                         : position;
  m_previousLocation = location;
  if (time - m_directionTime > 10.0)
    m_direction = {};
  if (m_attributes.size() > 4)
  {
    m_attributes.clear();
    m_graph.ClearState();
  }
  std::erase_if(m_attributes, [](auto const & entry) { return !entry.first.IsAlive(); });
  std::vector<IRoadGraph::EdgeProjectionT> candidates;
  m_graph.FindClosestEdges(mercator::RectByCenterXYAndSizeInMeters(position, 40.0), 16, candidates);
  auto match = MatchRoad(position, m_direction, location.m_horizontalAccuracy, candidates);
#ifdef OMIM_AUTO
  if (m_events)
    result.m_coverageChanged = UpdateCoverage({position, m_direction, time}, continuous, match, m_events->Get());
#endif
  bool const stable = match && m_previousMatch &&
                      match->first.GetFeatureId() == m_previousMatch->first.GetFeatureId() &&
                      match->first.IsForward() == m_previousMatch->first.IsForward();
  m_previousMatch = match;
  if (!stable)
  {
    m_externalLimit = 0;
    return result;
  }
  auto const & edge = match->first;
  auto & attrs = GetAttributes(edge.GetFeatureId().m_mwmId);
  result.m_matched = true;
  if (attrs.m_speeds)
  {
    auto const speed = attrs.m_speeds->GetMaxspeed(edge.GetFeatureId().m_index)
                           .GetCurrentSpeed(static_cast<time_t>(time), edge.IsForward());
    if (speed.IsNumeric())
      result.m_speedLimitMps = speed.GetSpeedKmPH() / 3.6;
  }
  if (auto feature = m_source.GetFeature(edge.GetFeatureId()))
    result.m_road = feature->GetReadableName();
  FindRoadCamera(m_graph, edge, match->second.GetPoint(), [this](Edge const & e)
  {
    auto const & cameras = GetAttributes(e.GetFeatureId().m_mwmId).m_cameras;
    auto const it = cameras.find({e.GetFeatureId().m_index, e.GetSegId()});
    return it == cameras.end() ? std::vector<RouteSegment::SpeedCamera>{} : it->second;
  }, result);
  if (m_events)
  {
    auto const events = m_events->Get();
    if (events.m_store != m_eventStore || !events.m_enabled)
      m_externalLimit = 0;
    m_eventStore = events.m_store;
    if (events.m_enabled && events.m_store)
      ReadEvents(*match, previousPosition, events, result);
    result.m_externalSpeedLimitMps = m_externalLimit;
    if (m_externalLimit > 0)
      result.m_speedLimitMps = m_externalLimit;
  }
  return result;
}

#ifdef OMIM_AUTO
bool RoadInfoReader::UpdateCoverage(CameraCoverageFix const & fix, bool continuous,
                                    std::optional<IRoadGraph::EdgeProjectionT> const & match,
                                    RoadEventSource::Snapshot const & events)
{
  if (!events.m_filterCoverage || !events.m_coverageVisible)
    return false;
  auto const & position = fix.m_position;
  bool const sameInput = m_coverageInput.m_coverageGeneration == events.m_coverageGeneration &&
                         m_coverageInput.m_store == events.m_store &&
                         m_coverageInput.m_mapCameras == events.m_mapCameras;
  if (!continuous || !sameInput)
    m_coverageLastFix.reset();
  if (!events.m_coverageRoute && !match)
  {
    if (m_coverageLastFix && CanRetainCameraCoverage(*m_coverageLastFix, fix))
      return false;
    m_coverageLastFix.reset();
    m_coveragePosition.reset();
    return m_events->ClearCoverage();
  }
  // Refresh on every good fix, independently of the 100 m selection cache.
  m_coverageLastFix = match ? std::make_optional(fix) : std::nullopt;
  bool const sameRoad =
      (!match && !m_coverageMatch) ||
      (match && m_coverageMatch && match->first.GetFeatureId() == m_coverageMatch->first.GetFeatureId() &&
       match->first.IsForward() == m_coverageMatch->first.IsForward());
  if (m_coveragePosition && sameRoad && sameInput && mercator::DistanceOnEarth(*m_coveragePosition, position) < 100)
    return false;

  auto path = events.m_coverageRoute;
  if (!path)
  {
    auto const & edge = match->first;
    auto feature = m_source.GetFeature(edge.GetFeatureId());
    std::string const name(feature ? feature->GetReadableName() : "");
    std::string const ref = feature ? feature->GetRef() : "";
    path = std::make_shared<CameraCoveragePath>(MakeCurrentRoadCoveragePath(m_graph, edge, [&](Edge const & next)
    {
      if (next.GetFeatureId() == edge.GetFeatureId())
        return true;
      auto other = m_source.GetFeature(next.GetFeatureId());
      return other && ((!ref.empty() && other->GetRef() == ref) || (!name.empty() && other->GetReadableName() == name));
    }));
  }
  RoadEventSource::Coverage coverage;
  // Local horizon bounds graph lookups even for a route spanning a whole country.
  auto const rect = mercator::RectByCenterXYAndSizeInMeters(position, kCameraCoverageRadiusMeters);
  auto collect = [&](std::shared_ptr<RoadEventStore const> const & store, std::vector<size_t> & indices)
  {
    if (!store)
      return;
    for (auto index : store->Query(rect, 1u << static_cast<unsigned>(RoadEventCategory::Cameras),
                                   std::numeric_limits<size_t>::max()))
    {
      auto const & camera = store->Get(index);
      if (!IsWithinCameraCoverageRange(position, camera.m_position))
        continue;
      if (!camera.m_directionType || !camera.m_distance ||
          (camera.m_kind != RoadEventKind::Camera && camera.m_kind != RoadEventKind::Mobile &&
           camera.m_kind != RoadEventKind::RedLight && camera.m_kind != RoadEventKind::LaneControl))
        continue;
      double const radius = RoadEventMatchRadius(camera);
      if (!path->IsNear(camera.m_position, radius))
        continue;
      std::vector<IRoadGraph::EdgeProjectionT> candidates;
      m_graph.FindClosestEdges(mercator::RectByCenterXYAndSizeInMeters(camera.m_position, radius), 16, candidates);
      if (path->MatchesCamera(camera, candidates))
        indices.push_back(index);
    }
  };
  collect(events.m_store, coverage.m_imported);
  collect(events.m_mapCameras, coverage.m_map);
  m_coverageMatch = match;
  m_coveragePosition = position;
  m_coverageInput = events;
  return m_events->SetCoverage(events, std::move(coverage));
}
#endif

void RoadInfoReader::ReadEvents(IRoadGraph::EdgeProjectionT const & match, m2::PointD const & previous,
                                RoadEventSource::Snapshot const & events, RoadInfoSnapshot & result)
{
  auto edge = match.first;
  auto const position = match.second.GetPoint();
  double passed = -mercator::DistanceOnEarth(edge.GetStartPoint(), position);
  std::set<Edge> visited;
  double latestCrossed = -std::numeric_limits<double>::max();
  for (size_t n = 0; n < 256 && passed < 2000 && visited.insert(edge).second; ++n)
  {
    auto const start = edge.GetStartPoint();
    auto const direction = edge.GetDirection();
    auto const lengthSquared = direction.SquaredLength();
    if (lengthSquared == 0)
      break;
    auto rect = m2::RectD(start, edge.GetEndPoint());
#ifdef OMIM_AUTO
    double constexpr queryRadius = kMaxCameraRoadDistanceMeters;
#else
    double constexpr queryRadius = 15;
#endif
    rect.Add(mercator::RectByCenterXYAndSizeInMeters(start, queryRadius));
    rect.Add(mercator::RectByCenterXYAndSizeInMeters(edge.GetEndPoint(), queryRadius));
    double const length = mercator::DistanceOnEarth(start, edge.GetEndPoint());
    double const bearing = math::RadToDeg(std::atan2(direction.x, direction.y));
    for (auto index : events.m_store->Query(rect, kAllRoadEventCategories, 4096))
    {
      auto const & event = events.m_store->Get(index);
#ifdef OMIM_AUTO
      if (!event.MatchesRoadBearing(bearing))
#else
      if (!event.MatchesBearing(bearing))
#endif
        continue;
#ifdef OMIM_AUTO
      double const matchRadius = RoadEventMatchRadius(event);
      auto const projection = ProjectRoadEvent(event.m_position, edge, matchRadius);
      if (!projection)
        continue;
      double const coefficient = *projection;
#else
      double constexpr matchRadius = kRoadEventRoadDistanceMeters;
      double const coefficient = DotProduct(event.m_position - start, direction) / lengthSquared;
      if (coefficient < 0 || coefficient > 1)
        continue;
#endif
      auto const projected = start + direction * coefficient;
      if (mercator::DistanceOnEarth(projected, event.m_position) > matchRadius)
        continue;
      // A nearby parallel road must not donate its signs to the matched carriageway.
      std::vector<IRoadGraph::EdgeProjectionT> candidates;
      m_graph.FindClosestEdges(mercator::RectByCenterXYAndSizeInMeters(event.m_position, std::max(25.0, matchRadius)),
                               16, candidates);
#ifdef OMIM_AUTO
      auto eventMatch = MatchRoad(event.m_position, direction, 5, candidates, matchRadius, true);
#else
      auto eventMatch = MatchRoad(event.m_position, direction, 5, candidates);
#endif
      if (!eventMatch)
        continue;
      bool sameRoad =
          eventMatch->first.GetFeatureId() == edge.GetFeatureId() && eventMatch->first.IsForward() == edge.IsForward();
#ifdef OMIM_AUTO
      sameRoad |= AreConsecutiveRoadEdges(eventMatch->first, edge);
#endif
      if (!sameRoad)
        continue;
      double const distance = passed + length * coefficient;
      bool const crossed =
          n == 0 && DotProduct(previous - projected, direction) < 0 && DotProduct(position - projected, direction) >= 0;
      if (crossed && distance > latestCrossed &&
          (event.m_kind == RoadEventKind::SpeedLimit || event.m_kind == RoadEventKind::SettlementStart ||
           event.m_kind == RoadEventKind::SettlementEnd))
      {
        latestCrossed = distance;
        // A settlement baseline never raises an already known lower posted limit.
        if (event.m_kind == RoadEventKind::SettlementStart)
        {
          double const limit = event.m_speedKmh / 3.6;
          double const current = m_externalLimit > 0 ? m_externalLimit : result.m_speedLimitMps;
          if (limit > 0)
            m_externalLimit = current > 0 ? std::min(current, limit) : limit;
        }
        else
          m_externalLimit = event.m_speedKmh / 3.6;
      }
      if (distance < 0 || distance > std::min<double>(2000, event.m_distance))
        continue;
      if (result.m_eventDistance < 0 || distance < result.m_eventDistance)
      {
        result.m_eventDistance = distance;
        result.m_event = event;
      }
      if (events.m_warnings && (events.m_visibleKinds & (1u << static_cast<unsigned>(event.m_kind))) &&
          event.IsInApproachSector(position))
      {
        // Return a bounded ordered set: an already-announced or slow-speed event must not hide the next warning.
        auto & warnings = result.m_warnings;
        auto const insertion =
            std::lower_bound(warnings.begin(), warnings.end(), distance,
                             [](auto const & warning, double dist) { return warning.m_distance < dist; });
        bool const duplicate = std::any_of(warnings.begin(), warnings.end(), [&](auto const & warning)
        {
          return warning.m_event.m_sourceId == event.m_sourceId && warning.m_event.m_kind == event.m_kind &&
                 warning.m_event.m_position == event.m_position;
        });
        if (!duplicate && (insertion != warnings.end() || warnings.size() < 16))
        {
          warnings.insert(insertion, {event, distance});
          if (warnings.size() > 16)
            warnings.pop_back();
        }
      }
      // Filter before choosing the nearest speed camera; other enforcement and dummy points must not mask it.
      if (IsSpeedCamera(event.m_kind) && (result.m_cameraDistance < 0 || distance < result.m_cameraDistance))
      {
        result.m_cameraDistance = distance;
        result.m_cameraLimitMps = event.m_speedKmh / 3.6;
        result.m_cameraPosition = event.m_position;
      }
    }
    passed += length;
    IRoadGraph::EdgeListT outgoing;
    m_graph.GetOutgoingEdges(edge.GetEndJunction(), outgoing);
    std::set<Edge> choices;
    for (auto const & next : outgoing)
      if (!next.IsFake() && next != edge.GetReverseEdge())
        choices.insert(next);
    if (choices.size() != 1)
      break;
    edge = *choices.begin();
  }
}
}  // namespace routing
