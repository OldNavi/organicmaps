#pragma once

#include "geometry/tree4d.hpp"
#include "routing/road_events.hpp"
#include "routing/road_graph.hpp"

#include <vector>

namespace routing
{
inline constexpr double kCameraCoverageRadiusMeters = 2000;

bool IsWithinCameraCoverageRange(m2::PointD const & position, m2::PointD const & camera);

#ifdef OMIM_AUTO
struct CameraCoverageFix
{
  m2::PointD m_position;
  m2::PointD m_direction;
  double m_time;
};

// Bridge a brief ambiguous road match for visuals only, while motion remains continuous.
bool CanRetainCameraCoverage(CameraCoverageFix const & previous, CameraCoverageFix const & current);
#endif

// Directed real road segments, including partial segments at the ends of a route.
class CameraCoveragePath
{
public:
  explicit CameraCoveragePath(std::vector<Edge> edges);
  bool Contains(Edge const & edge, m2::PointD const & projection) const;
  bool IsNear(m2::PointD const & position, double maximumDistance = kRoadEventRoadDistanceMeters) const;
#ifdef OMIM_AUTO
  bool MatchesCamera(RoadEvent const & event, std::vector<IRoadGraph::EdgeProjectionT> const & candidates) const;
#endif

private:
  std::vector<Edge> m_edges;
  m4::Tree<size_t> m_index;
};

using SameStreet = std::function<bool(Edge const &)>;
CameraCoveragePath MakeCurrentRoadCoveragePath(IRoadGraph const & graph, Edge const & current,
                                               SameStreet const & sameStreet);
}  // namespace routing
