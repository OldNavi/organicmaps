#include "routing/camera_coverage.hpp"
#include "routing/road_info.hpp"

#include "base/math.hpp"

#include "geometry/mercator.hpp"
#include "geometry/parametrized_segment.hpp"

#include <algorithm>
#include <cmath>
#include <set>
#include <tuple>

namespace routing
{
namespace
{
auto Key(Edge const & edge)
{
  return std::tuple(edge.GetFeatureId(), edge.GetSegId(), edge.IsForward());
}
}  // namespace

bool IsWithinCameraCoverageRange(m2::PointD const & position, m2::PointD const & camera)
{
  return mercator::DistanceOnEarth(position, camera) <= kCameraCoverageRadiusMeters;
}

CameraCoveragePath::CameraCoveragePath(std::vector<Edge> edges) : m_edges(std::move(edges))
{
  std::sort(m_edges.begin(), m_edges.end(), [](auto const & a, auto const & b) { return Key(a) < Key(b); });
  for (size_t i = 0; i < m_edges.size(); ++i)
    m_index.Add(i, m2::RectD(m_edges[i].GetStartPoint(), m_edges[i].GetEndPoint()));
}

bool CameraCoveragePath::IsNear(m2::PointD const & position, double maximumDistance) const
{
  bool found = false;
  m_index.ForEachInRect(mercator::RectByCenterXYAndSizeInMeters(position, maximumDistance), [&](size_t index)
  {
    if (found)
      return;
    auto const & edge = m_edges[index];
    m2::ParametrizedSegment<m2::PointD> segment(edge.GetStartPoint(), edge.GetEndPoint());
    found = mercator::DistanceOnEarth(segment.ClosestPointTo(position), position) <= maximumDistance;
  });
  return found;
}

bool CameraCoveragePath::Contains(Edge const & edge, m2::PointD const & projection) const
{
  auto it = std::lower_bound(m_edges.begin(), m_edges.end(), edge,
                             [](auto const & a, auto const & b) { return Key(a) < Key(b); });
  for (; it != m_edges.end() && Key(*it) == Key(edge); ++it)
  {
    m2::ParametrizedSegment<m2::PointD> segment(it->GetStartPoint(), it->GetEndPoint());
    if (mercator::DistanceOnEarth(segment.ClosestPointTo(projection), projection) <= 1.0)
      return true;
  }
  return false;
}

#ifdef OMIM_AUTO
bool CanRetainCameraCoverage(CameraCoverageFix const & previous, CameraCoverageFix const & current)
{
  double const age = current.m_time - previous.m_time;
  if (age <= 0 || age > 3.0 || mercator::DistanceOnEarth(previous.m_position, current.m_position) > 100.0 ||
      previous.m_direction.IsAlmostZero() || current.m_direction.IsAlmostZero())
    return false;
  return m2::DotProduct(previous.m_direction.Normalize(), current.m_direction.Normalize()) >=
         std::cos(math::DegToRad(30.0));
}

bool CameraCoveragePath::MatchesCamera(RoadEvent const & event,
                                       std::vector<IRoadGraph::EdgeProjectionT> const & candidates) const
{
  bool found = false;
  double const radius = RoadEventMatchRadius(event);
  m_index.ForEachInRect(mercator::RectByCenterXYAndSizeInMeters(event.m_position, radius), [&](size_t index)
  {
    if (found)
      return;
    auto const & edge = m_edges[index];
    auto const direction = edge.GetDirection();
    if (direction.IsAlmostZero() || !event.MatchesRoadBearing(math::RadToDeg(std::atan2(direction.x, direction.y))))
      return;
    // Use the road's direction: a camera on a building can look obliquely across the carriageway.
    auto match = MatchRoad(event.m_position, direction, 5, candidates, radius, true);
    found =
        match &&
        (Contains(match->first, match->second.GetPoint()) ||
         (AreConsecutiveRoadEdges(match->first, edge) && ProjectRoadEvent(event.m_position, edge, radius).has_value()));
  });
  return found;
}
#endif

CameraCoveragePath MakeCurrentRoadCoveragePath(IRoadGraph const & graph, Edge const & current,
                                               SameStreet const & sameStreet)
{
  std::vector<Edge> path{current};
  for (bool const forward : {true, false})
  {
    auto edge = current;
    std::set<Edge> visited{edge};
    double distance = 0;
    while (visited.size() < 256 && distance < kCameraCoverageRadiusMeters)
    {
      IRoadGraph::EdgeListT adjacent;
      if (forward)
        graph.GetOutgoingEdges(edge.GetEndJunction(), adjacent);
      else
        graph.GetIngoingEdges(edge.GetStartJunction(), adjacent);
      std::set<Edge> choices;
      for (auto const & next : adjacent)
        if (!next.IsFake() && next != edge.GetReverseEdge() && sameStreet(next))
          choices.insert(next);
      // A fork with two continuations of the same street is still ambiguous.
      if (choices.size() != 1 || !visited.insert(*choices.begin()).second)
        break;
      edge = *choices.begin();
      distance += mercator::DistanceOnEarth(edge.GetStartPoint(), edge.GetEndPoint());
      path.push_back(edge);
    }
  }
  return CameraCoveragePath(std::move(path));
}
}  // namespace routing
