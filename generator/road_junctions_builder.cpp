#include "generator/road_junctions_builder.hpp"

#include "indexer/classificator.hpp"

#include "geometry/mercator.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <set>

namespace generator
{
namespace
{
double constexpr kMaxCutDistance = 30.0;
}

void RoadJunctionsBuilder::Add(uint32_t featureId, feature::FeatureBuilder const & feature)
{
  if (!feature.IsLine() || !feature.GetRoadDetails())
    return;
  auto const & details = *feature.GetRoadDetails();
  auto road = std::make_shared<Road>();
  road->m_details = details;
  road->m_arm.m_featureId = featureId;
  m_roads.emplace(featureId, road);
  auto const highway = classif().GetTypeByPath({"highway"});
  for (auto type : feature.GetTypes())
  {
    auto root = type;
    ftype::TruncValue(root, 1);
    if (root == highway)
    {
      ftype::TruncValue(type, 2);
      road->m_roadType = type;
      break;
    }
  }
  auto const & points = feature.GetOuterGeometry();
  if (details.m_placementTransition)
    road->m_points = points;
  auto const & nodes = feature.GetRoadNodeIds();
  if (points.size() != nodes.size())
    return;  // Geometry synthesized by another generator stage has no matching OSM node sequence.
  auto path = std::make_shared<m2::MetricPolyline>(points, details.m_roundabout, details.m_startOffsetCm * 0.01,
                                                   details.m_endOffsetCm * 0.01);
  if (!path->IsValid() || path->Length() < 0.1)
    return;
  road->m_path = path;
  road->m_layer = feature.GetParams().layer;
  road->m_arm.m_oneWay = details.m_oneWay;
  road->m_arm.m_markings = details.m_markings;
  for (auto const & lane : details.m_lanes)
  {
    road->m_arm.m_widthsCm.push_back(lane.m_widthCm);
    road->m_arm.m_turns.push_back(lane.m_turns);
  }
  double distance = 0;
  for (size_t i = 0; i < points.size(); ++i)
  {
    if (i != 0)
      distance += mercator::DistanceOnEarth(points[i - 1], points[i]);
    if (nodes[i] == 0)
      continue;
    for (bool forward : {false, true})
    {
      if ((forward && i + 1 == points.size()) || (!forward && i == 0))
        continue;
      Endpoint endpoint;
      endpoint.m_road = road;
      endpoint.m_forward = forward;
      endpoint.m_featureEndpoint = i == 0 || i + 1 == points.size();
      endpoint.m_nodeDistance = i == 0                 ? 0
                              : i + 1 == points.size() ? path->Length()
                                                       : path->ProjectDistance(points[i], distance);
      endpoint.m_nodePosition = points[i];
      m_nodes[nodes[i]].push_back(std::move(endpoint));
    }
  }
}

void RoadJunctionsBuilder::ForEachRoad(std::function<void(uint32_t, feature::RoadDetails const &)> const & fn) const
{
  for (auto const & [id, road] : m_roads)
    fn(id, road->m_details);
}

void RoadJunctionsBuilder::ResolveInferredWidths()
{
  // Only bridge gaps in tagging along the same road class. A ramp or crossing is not a width hint.
  using Neighbors = std::array<Road const *, 2>;
  std::map<uint32_t, Neighbors> neighbors;
  for (auto const & [node, endpoints] : m_nodes)
  {
    for (auto const & endpoint : endpoints)
    {
      if (!endpoint.m_featureEndpoint)
        continue;
      auto const & road = *endpoint.m_road;
      auto const direction = road.m_path->Sample(endpoint.m_nodeDistance).m_tangent * (endpoint.m_forward ? 1 : -1);
      double best = -0.90, second = 0;
      Road const * candidate = nullptr;
      for (auto const & other : endpoints)
      {
        auto const & next = *other.m_road;
        if (!other.m_featureEndpoint || &road == &next || road.m_roadType != next.m_roadType ||
            road.m_arm.m_oneWay != next.m_arm.m_oneWay ||
            (road.m_arm.m_oneWay && endpoint.m_forward == other.m_forward))
          continue;
        auto const away = next.m_path->Sample(other.m_nodeDistance).m_tangent * (other.m_forward ? 1 : -1);
        double const dot = DotProduct(direction, away);
        if (dot < best)
        {
          second = best;
          best = dot;
          candidate = &next;
        }
        else
          second = std::min(second, dot);
      }
      if (candidate && (second >= -0.90 || second - best > 0.02))
        neighbors[road.m_arm.m_featureId][endpoint.m_forward ? 0 : 1] = candidate;
    }
  }
  auto const needsWidth = [](Road const & road)
  {
    auto const & details = road.m_details;
    return details.m_inferredLaneCount &&
           std::all_of(details.m_lanes.begin(), details.m_lanes.end(),
                       [](auto const & lane) { return lane.m_source == feature::RoadDetails::WidthSource::Default; });
  };
  std::map<uint32_t, std::vector<feature::RoadDetails::Lane>> resolved;
  for (auto const & [id, road] : m_roads)
  {
    if (!road->m_path || !needsWidth(*road))
      continue;
    auto const anchor = [&](size_t side) -> Road const *
    {
      auto it = neighbors.find(id);
      if (it == neighbors.end())
        return nullptr;
      Road const * previous = road.get();
      Road const * current = it->second[side];
      double distance = road->m_path->Length();
      std::set<uint32_t> visited{id};
      while (current && distance <= 1000)
      {
        auto const currentId = current->m_arm.m_featureId;
        if (!visited.insert(currentId).second)
          return nullptr;
        if (!needsWidth(*current))
          return current;
        it = neighbors.find(currentId);
        if (it == neighbors.end())
          return nullptr;
        auto const & pair = it->second;
        auto const next = pair[0] == previous ? pair[1] : pair[1] == previous ? pair[0] : nullptr;
        distance += current->m_path->Length();
        previous = current;
        current = next;
      }
      return nullptr;
    };
    auto const a = anchor(0), b = anchor(1);
    if (!a || !b)
      continue;
    // Retain a conservative width if the mapped road changes width across the unmapped stretch.
    auto const & reference = a->m_details.WidthMeters() < b->m_details.WidthMeters() ? a : b;
    resolved.emplace(id, reference->m_details.m_lanes);
  }
  for (auto & [id, lanes] : resolved)
  {
    auto & road = *m_roads.at(id);
    for (auto & lane : lanes)
    {
      lane.m_source = feature::RoadDetails::WidthSource::Default;
      lane.m_turns = 0;
      lane.m_publicTransport = false;
    }
    road.m_details.m_lanes = std::move(lanes);
    road.m_arm.m_widthsCm.clear();
    road.m_arm.m_turns.clear();
    for (auto const & lane : road.m_details.m_lanes)
    {
      road.m_arm.m_widthsCm.push_back(lane.m_widthCm);
      road.m_arm.m_turns.push_back(0);
    }
  }
}

void RoadJunctionsBuilder::ResolvePlacementTransitions()
{
  std::map<uint32_t, std::array<int16_t, 2>> offsets;
  for (auto const & [node, endpoints] : m_nodes)
  {
    for (auto const & endpoint : endpoints)
    {
      auto const & road = *endpoint.m_road;
      if (!endpoint.m_featureEndpoint || !road.m_details.m_placementTransition || road.m_details.m_startOffsetCm != 0 ||
          road.m_details.m_endOffsetCm != 0)
        continue;
      auto const frame = road.m_path->Sample(endpoint.m_nodeDistance);
      auto const away = frame.m_tangent * (endpoint.m_forward ? 1 : -1);
      Endpoint const * neighbor = nullptr;
      double best = -0.5, second = 0;
      for (auto const & other : endpoints)
      {
        if (other.m_road.get() == &road || other.m_road->m_details.m_placementTransition ||
            other.m_road->m_arm.m_oneWay != road.m_arm.m_oneWay || endpoint.m_forward == other.m_forward)
          continue;
        auto const otherAway =
            other.m_road->m_path->Sample(other.m_nodeDistance).m_tangent * (other.m_forward ? 1 : -1);
        double const dot = DotProduct(away, otherAway);
        if (dot < best)
        {
          second = best;
          best = dot;
          neighbor = &other;
        }
        else
          second = std::min(second, dot);
      }
      if (!neighbor || (second < -0.5 && second - best < 0.05))
        continue;
      auto const center = neighbor->m_road->m_path->Sample(neighbor->m_nodeDistance).m_position;
      double const meters =
          DotProduct(center - endpoint.m_nodePosition, frame.m_leftPerMeter) / frame.m_leftPerMeter.SquaredLength();
      if (std::abs(meters) <= road.m_details.WidthMeters())
        offsets[road.m_arm.m_featureId][endpoint.m_forward ? 0 : 1] = static_cast<int16_t>(std::lround(meters * 100));
    }
  }
  for (auto const & [id, values] : offsets)
  {
    auto & road = *m_roads.at(id);
    auto path = std::make_shared<m2::MetricPolyline>(road.m_points, road.m_details.m_roundabout, values[0] * 0.01,
                                                     values[1] * 0.01);
    if (path->IsValid())
    {
      road.m_details.m_startOffsetCm = values[0];
      road.m_details.m_endOffsetCm = values[1];
      road.m_path = std::move(path);
    }
  }
  for (auto & [node, endpoints] : m_nodes)
    for (auto & endpoint : endpoints)
      if (offsets.contains(endpoint.m_road->m_arm.m_featureId))
      {
        auto const & path = *endpoint.m_road->m_path;
        endpoint.m_nodeDistance = endpoint.m_featureEndpoint
                                    ? (endpoint.m_forward ? 0 : path.Length())
                                    : path.ProjectDistance(endpoint.m_nodePosition, endpoint.m_nodeDistance);
      }
}

std::vector<feature::RoadJunction> RoadJunctionsBuilder::Build()
{
  ResolveInferredWidths();
  ResolvePlacementTransitions();
  std::map<uint64_t, std::vector<Endpoint> const *> nodes;
  std::map<uint32_t, std::set<double>> cuts;
  for (auto const & [node, endpoints] : m_nodes)
  {
    if (endpoints.size() < 2 || endpoints.size() > 12)
      continue;
    if (endpoints.size() == 2 && endpoints[0].m_road->m_arm.m_featureId == endpoints[1].m_road->m_arm.m_featureId)
      continue;  // An ordinary internal vertex or the seam of a standalone closed way.
    auto const center = endpoints.front().m_nodePosition;
    if (std::any_of(endpoints.begin(), endpoints.end(), [&](auto const & endpoint)
    { return mercator::DistanceOnEarth(center, endpoint.m_nodePosition) > 0.3; }))
      continue;
    nodes.emplace(node, &endpoints);
    for (auto const & endpoint : endpoints)
      cuts[endpoint.m_road->m_arm.m_featureId].insert(endpoint.m_nodeDistance);
  }

  std::vector<feature::RoadJunction> result;
  for (auto const & [node, endpointsPtr] : nodes)
  {
    auto const & endpoints = *endpointsPtr;
    auto const owner = std::max_element(endpoints.begin(), endpoints.end(), [](auto const & a, auto const & b)
    {
      if (a.m_road->m_layer != b.m_road->m_layer)
        return a.m_road->m_layer < b.m_road->m_layer;
      if (a.m_road->m_arm.WidthMeters() != b.m_road->m_arm.WidthMeters())
        return a.m_road->m_arm.WidthMeters() < b.m_road->m_arm.WidthMeters();
      return a.m_road->m_arm.m_featureId > b.m_road->m_arm.m_featureId;
    });
    double width = 0;
    for (auto const & endpoint : endpoints)
      width = std::max(width, endpoint.m_road->m_arm.WidthMeters());
    double const radius = std::clamp(width * 1.5, 8.0, kMaxCutDistance);
    feature::RoadJunction junction;
    junction.m_center = endpoints.front().m_nodePosition;
    junction.m_ownerFeatureId = owner->m_road->m_arm.m_featureId;
    for (auto const & endpoint : endpoints)
    {
      auto arm = endpoint.m_road->m_arm;
      arm.m_forward = endpoint.m_forward;
      arm.m_featureEndpoint = endpoint.m_featureEndpoint;
      arm.m_nodeDistance = endpoint.m_nodeDistance;
      auto const & distances = cuts.at(arm.m_featureId);
      double available = 0;
      if (arm.m_forward)
      {
        auto const next = distances.upper_bound(arm.m_nodeDistance + 0.001);
        available = (next == distances.end() ? endpoint.m_road->m_path->Length() : *next) - arm.m_nodeDistance;
      }
      else
      {
        auto prev = distances.lower_bound(arm.m_nodeDistance - 0.001);
        available = arm.m_nodeDistance - (prev == distances.begin() ? 0.0 : *--prev);
      }
      arm.m_cutDistance = std::min(radius, available * 0.4);
      if (arm.m_cutDistance < 0.01)
        break;
      auto const frame =
          endpoint.m_road->m_path->Sample(arm.m_nodeDistance + (arm.m_forward ? 1 : -1) * arm.m_cutDistance);
      arm.m_position = frame.m_position;
      arm.m_nodeDirectionAway =
          endpoint.m_road->m_path->Sample(arm.m_nodeDistance).m_tangent * (arm.m_forward ? 1 : -1);
      arm.m_directionAway = frame.m_tangent * (arm.m_forward ? 1 : -1);
      arm.m_normalAwayPerMeter = frame.m_leftPerMeter * (arm.m_forward ? 1 : -1);
      junction.m_arms.push_back(std::move(arm));
    }
    if (junction.m_arms.size() != endpoints.size())
      continue;
    double bestContinuation = 0;
    uint8_t continuationA = 255, continuationB = 255;
    for (size_t a = 0; a < junction.m_arms.size(); ++a)
    {
      auto const & first = junction.m_arms[a];
      for (size_t b = a + 1; b < junction.m_arms.size(); ++b)
      {
        auto const & second = junction.m_arms[b];
        if (first.m_oneWay != second.m_oneWay || (first.m_oneWay && first.m_forward == second.m_forward))
          continue;
        double const dot = DotProduct(first.m_nodeDirectionAway, second.m_nodeDirectionAway);
        if (dot > -0.90)
          continue;
        double const score =
            std::max(first.WidthMeters(), second.WidthMeters()) * (first.m_featureId == second.m_featureId ? 100 : 1) -
            dot;
        if (score > bestContinuation)
        {
          bestContinuation = score;
          continuationA = a;
          continuationB = b;
        }
      }
    }
    if (continuationA != 255)
    {
      auto const & a = junction.m_arms[continuationA];
      auto const & b = junction.m_arms[continuationB];
      auto widths = b.m_widthsCm;
      if (!a.m_oneWay && a.m_forward == b.m_forward)
        std::reverse(widths.begin(), widths.end());
      // Unequal widths need the transition mesh; do not choose an equal-width side road instead.
      bool const widerBranch = std::any_of(junction.m_arms.begin(), junction.m_arms.end(),
                                           [&](auto const & arm) { return arm.WidthMeters() > a.WidthMeters(); });
      bool const roundabout =
          m_roads.at(a.m_featureId)->m_details.m_roundabout || m_roads.at(b.m_featureId)->m_details.m_roundabout;
      int const aOffset = (a.m_forward ? m_roads.at(a.m_featureId)->m_details.m_startOffsetCm
                                       : m_roads.at(a.m_featureId)->m_details.m_endOffsetCm);
      int const bOffset = (b.m_forward ? m_roads.at(b.m_featureId)->m_details.m_startOffsetCm
                                       : m_roads.at(b.m_featureId)->m_details.m_endOffsetCm) *
                          (a.m_forward == b.m_forward ? -1 : 1);
      if (a.m_widthsCm == widths && aOffset == bOffset && (!widerBranch || roundabout))
      {
        junction.m_continuationA = continuationA;
        junction.m_continuationB = continuationB;
      }
    }
    result.push_back(std::move(junction));
  }
  return result;
}
}  // namespace generator
