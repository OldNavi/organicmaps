#include "geometry/mercator.hpp"
#include "routing/camera_coverage.hpp"
#include "routing/road_info.hpp"
#include "routing/routing_tests/road_graph_builder.hpp"
#include "testing/testing.hpp"

namespace road_info_tests
{
using namespace routing;
using namespace routing_test;

Edge MakeEdge(uint32_t id, m2::PointD a, m2::PointD b, uint32_t segment = 0)
{
  return Edge::MakeReal(MakeTestFeatureID(id), true, segment, geometry::MakePointWithAltitudeForTesting(a),
                        geometry::MakePointWithAltitudeForTesting(b));
}

UNIT_TEST(RoadInfo_MatchingRejectsAmbiguityAndWrongDirection)
{
  auto east = MakeEdge(0, {0, 0}, {0.001, 0});
  auto west = east.GetReverseEdge();
  auto parallel = MakeEdge(1, {0, 0.00002}, {0.001, 0.00002});
  std::vector<IRoadGraph::EdgeProjectionT> candidates = {
      {west, geometry::MakePointWithAltitudeForTesting({0.0005, 0})},
      {east, geometry::MakePointWithAltitudeForTesting({0.0005, 0})}};
  auto match = MatchRoad({0.0005, 0}, {1, 0}, 5, candidates);
  TEST(match && match->first == east, ());
  TEST(!MatchRoad({0.0005, 0}, {0, 1}, 5, candidates), ());
  TEST(!MatchRoad({0.0005, 0}, {}, 5, candidates), ());
  TEST(!MatchRoad({0.0005, 0}, {1, 0}, 100, candidates), ());
  candidates.emplace_back(parallel, geometry::MakePointWithAltitudeForTesting({0.0005, 0.00002}));
  TEST(!MatchRoad({0.0005, 0}, {1, 0}, 5, candidates), ());
}

UNIT_TEST(RoadInfo_CameraDistanceFollowsBend)
{
  RoadGraphMockSource graph;
  graph.AddRoad(MakeRoadInfoForTesting(true, 50, {{0, 0}, {0.001, 0}, {0.001, 0.001}}));
  RoadInfoSnapshot result;
  FindRoadCamera(graph, MakeEdge(0, {0, 0}, {0.001, 0}), {0.0005, 0}, [](Edge const & edge)
  {
    return edge.GetSegId() == 1 ? std::vector<RouteSegment::SpeedCamera>{{0.5, 60}}
                                : std::vector<RouteSegment::SpeedCamera>{};
  }, result);
  double const expected =
      mercator::DistanceOnEarth({0.0005, 0}, {0.001, 0}) + mercator::DistanceOnEarth({0.001, 0}, {0.001, 0.0005});
  TEST_ALMOST_EQUAL_ABS(result.m_cameraDistance, expected, 0.1, ());
  TEST_ALMOST_EQUAL_ABS(result.m_cameraLimitMps, 60.0 / 3.6, 0.001, ());
}

UNIT_TEST(RoadInfo_CameraDoesNotGuessJunction)
{
  RoadGraphMockSource graph;
  graph.AddRoad(MakeRoadInfoForTesting(true, 50, {{0, 0}, {0.001, 0}}));
  graph.AddRoad(MakeRoadInfoForTesting(true, 50, {{0.001, 0}, {0.002, 0}}));
  graph.AddRoad(MakeRoadInfoForTesting(true, 50, {{0.001, 0}, {0.001, 0.001}}));
  RoadInfoSnapshot result;
  FindRoadCamera(graph, MakeEdge(0, {0, 0}, {0.001, 0}), {0.0005, 0}, [](Edge const & edge)
  {
    return edge.GetFeatureId().m_index == 1 ? std::vector<RouteSegment::SpeedCamera>{{0.5, 60}}
                                            : std::vector<RouteSegment::SpeedCamera>{};
  }, result);
  TEST_LESS(result.m_cameraDistance, 0, ());
}

UNIT_TEST(RoadInfo_ReversePassIgnoresCameraBehind)
{
  RoadGraphMockSource graph;
  graph.AddRoad(MakeRoadInfoForTesting(true, 50, {{0, 0}, {0.001, 0}}));
  RoadInfoSnapshot result;
  FindRoadCamera(graph, MakeEdge(0, {0, 0}, {0.001, 0}).GetReverseEdge(), {0.0008, 0},
                 [](Edge const &) { return std::vector<RouteSegment::SpeedCamera>{{0.2, 40}, {0.9, 60}}; }, result);
  TEST_ALMOST_EQUAL_ABS(result.m_cameraDistance, mercator::DistanceOnEarth({0.0008, 0}, {0.0002, 0}), 0.1, ());
  TEST_ALMOST_EQUAL_ABS(result.m_cameraLimitMps, 40.0 / 3.6, 0.001, ());
}
UNIT_TEST(CameraCoverage_DirectedSegmentsAndPartialRoute)
{
  auto const full = MakeEdge(0, {0, 0}, {0.001, 0});
  auto const partial = MakeEdge(0, {0.0002, 0}, {0.0008, 0});
  CameraCoveragePath path({partial});
  TEST(path.IsNear({0.0005, 0.00005}), ("Camera near the road"));
  TEST(!path.IsNear({0.0005, 0.001}), ("Camera on another street"));
  TEST(!path.IsNear({0.002, 0}), ("Beyond the partial route"));
  TEST(path.Contains(full, {0.0005, 0}), ());
  TEST(!path.Contains(full.GetReverseEdge(), {0.0005, 0}), ("Opposing traffic"));
  TEST(!path.Contains(MakeEdge(1, {0, 0}, {0.001, 0}), {0.0005, 0}), ("Another carriageway"));
  TEST(!path.Contains(full, {0.0001, 0}), ("Before route start"));
  TEST(!path.Contains(full, {0.0009, 0}), ("After route end"));
  TEST(!path.Contains(MakeEdge(0, {0, 0}, {0.001, 0}, 1), {0.0005, 0}), ("Different segment"));
}

UNIT_TEST(CameraCoverage_SearchCircleHasTwoKilometreRadius)
{
  for (auto const position : {mercator::FromLatLon(0, 0), mercator::FromLatLon(55.75, 37.6)})
  {
    TEST(IsWithinCameraCoverageRange(position, mercator::GetSmPoint(position, 1990, 0)), ());
    TEST(!IsWithinCameraCoverageRange(position, mercator::GetSmPoint(position, 2010, 0)), ());
    TEST(IsWithinCameraCoverageRange(position, mercator::GetSmPoint(position, 0, 1990)), ());
    TEST(!IsWithinCameraCoverageRange(position, mercator::GetSmPoint(position, 0, 2010)), ());
    TEST(!IsWithinCameraCoverageRange(position, mercator::GetSmPoint(position, 1800, 1800)),
         ("The bounding box corners lie outside the search circle"));
  }
}

UNIT_TEST(CameraCoverage_CurrentStreetContinuesAcrossSideRoad)
{
  RoadGraphMockSource graph;
  graph.AddRoad(MakeRoadInfoForTesting(true, 50, {{0, 0}, {0.001, 0}, {0.002, 0}}));
  graph.AddRoad(MakeRoadInfoForTesting(true, 50, {{0.002, 0}, {0.003, 0}}));
  graph.AddRoad(MakeRoadInfoForTesting(true, 50, {{0.002, 0}, {0.002, 0.001}}));
  auto current = MakeEdge(0, {0.001, 0}, {0.002, 0}, 1);
  auto sameStreet = [](Edge const & edge) { return edge.GetFeatureId().m_index <= 1; };
  auto path = MakeCurrentRoadCoveragePath(graph, current, sameStreet);
  auto const continuation = MakeEdge(1, {0.002, 0}, {0.003, 0});
  TEST(path.Contains(current, {0.0015, 0}), ());
  TEST(path.Contains(MakeEdge(0, {0, 0}, {0.001, 0}), {0.0005, 0}), ("Same road behind"));
  TEST(path.Contains(continuation, {0.0025, 0}), ("Same street across a feature boundary"));
  TEST(!path.Contains(continuation.GetReverseEdge(), {0.0025, 0}), ());
  TEST(!path.Contains(MakeEdge(2, {0.002, 0}, {0.002, 0.001}), {0.002, 0.0005}), ("Side street"));
  auto ambiguous = MakeCurrentRoadCoveragePath(graph, current, [](Edge const &) { return true; });
  TEST(!ambiguous.Contains(continuation, {0.0025, 0}), ("Do not guess at a fork"));
}

#ifdef OMIM_AUTO
UNIT_TEST(CameraCoverage_BriefAmbiguityKeepsVisualsButExpires)
{
  CameraCoverageFix const previous{{0, 0}, {1, 0}, 100};
  CameraCoverageFix current{mercator::GetSmPoint(previous.m_position, 25, 0), {1, 0}, 101};
  TEST(CanRetainCameraCoverage(previous, current), ("One ambiguous GNSS fix"));
  current.m_time = 103;
  current.m_position = mercator::GetSmPoint(previous.m_position, 75, 0);
  TEST(CanRetainCameraCoverage(previous, current), ("Gap is measured from the last good fix"));
  current.m_time = 103.1;
  TEST(!CanRetainCameraCoverage(previous, current), ("Repeated ambiguous fixes must not extend retention"));
  current.m_time = 100;
  TEST(!CanRetainCameraCoverage(previous, current), ("Duplicate timestamp"));
  current.m_time = 99;
  TEST(!CanRetainCameraCoverage(previous, current), ("Clock discontinuity"));
  current.m_time = 101;
  current.m_position = mercator::GetSmPoint(previous.m_position, 101, 0);
  TEST(!CanRetainCameraCoverage(previous, current), ("Position jump"));
  current.m_position = previous.m_position;
  current.m_direction = {0, 1};
  TEST(!CanRetainCameraCoverage(previous, current), ("Turning onto another street"));
  current.m_direction = {-1, 0};
  TEST(!CanRetainCameraCoverage(previous, current), ("U-turn"));
  current.m_direction = {};
  TEST(!CanRetainCameraCoverage(previous, current), ("No heading"));
}

UNIT_TEST(RoadInfo_CameraAtForkUsesCommonApproachWithoutRelaxingGps)
{
  auto main = MakeEdge(0, {0, 0}, {-0.001, 0});
  auto branch = MakeEdge(1, {0, 0}, {-0.001, 0.00001});
  m2::PointD camera(-0.000025, 0);
  std::vector<IRoadGraph::EdgeProjectionT> candidates{
      {main, geometry::MakePointWithAltitudeForTesting(camera)},
      {branch, geometry::MakePointWithAltitudeForTesting({camera.x, 0.00000025})}};
  TEST(!MatchRoad(camera, {-1, 0}, 5, candidates, 80), ("Vehicle position remains ambiguous"));
  TEST(MatchRoad(camera, {-1, 0}, 5, candidates, 80, true).has_value(), ("Camera covers the shared junction"));
  camera = {-0.0005, 0};
  candidates[0].second = geometry::MakePointWithAltitudeForTesting(camera);
  candidates[1].second = geometry::MakePointWithAltitudeForTesting({camera.x, 0.000005});
  TEST(!MatchRoad(camera, {-1, 0}, 5, candidates, 80, true), ("Beyond the junction the branches remain distinct"));
}

UNIT_TEST(RoadInfo_CameraCanStandBesideTheRoad)
{
  auto road = MakeEdge(0, {0, 0}, {0.001, 0});
  RoadEvent camera;
  camera.m_position = {0.0005, 0.00027};  // About 30 m to the side, for example on a building.
  camera.m_directionType = 1;
  camera.m_direction = 90;
  camera.m_distance = 400;
  camera.m_angle = 15;
  double const radius = RoadEventMatchRadius(camera);
  TEST_GREATER(radius, 30, ());
  TEST_LESS_OR_EQUAL(radius, kMaxCameraRoadDistanceMeters, ());
  TEST(ProjectRoadEvent(camera.m_position, road, radius).has_value(), ());
  TEST(!ProjectRoadEvent({0.0013, 0.00027}, road, radius),
       ("A lateral allowance must not extend the segment far beyond a junction"));
  std::vector<IRoadGraph::EdgeProjectionT> candidates{{road, geometry::MakePointWithAltitudeForTesting({0.0005, 0})}};
  TEST(!MatchRoad(camera.m_position, {1, 0}, 5, candidates), ("GPS matching retains its tight tolerance"));
  TEST(MatchRoad(camera.m_position, {1, 0}, 5, candidates, radius).has_value(), ());
  CameraCoveragePath path({road});
  camera.m_direction = 60;
  TEST(!camera.MatchesBearing(90), ());
  TEST(path.MatchesCamera(camera, candidates), ("An oblique camera can cover the selected road"));
  auto parallel = MakeEdge(1, {0, 0.0002}, {0.001, 0.0002});
  candidates.push_back({parallel, geometry::MakePointWithAltitudeForTesting({0.0005, 0.0002})});
  auto nearest = MatchRoad(camera.m_position, {1, 0}, 5, candidates, radius);
  TEST(nearest && nearest->first.GetFeatureId() == parallel.GetFeatureId(), ());
  TEST(!AreConsecutiveRoadEdges(nearest->first, road), ("Do not transfer a camera from the neighbouring road"));
  TEST(!path.MatchesCamera(camera, candidates), ("Coverage also respects the neighbouring carriageway"));
  camera.m_kind = RoadEventKind::Bump;
  TEST_EQUAL(RoadEventMatchRadius(camera), kRoadEventRoadDistanceMeters, ());
}

UNIT_TEST(RoadInfo_CameraAtFeatureBoundaryKeepsSameApproach)
{
  auto before = MakeEdge(0, {0.001, 0}, {0, 0});
  auto after = MakeEdge(1, {0, 0}, {-0.001, 0});
  m2::PointD camera(-0.000025, 0);  // A source coordinate 2.8 m beyond the first segment.
  auto projection = ProjectRoadEvent(camera, before);
  TEST(projection && *projection == 1.0, ());
  TEST(!ProjectRoadEvent({-0.0002, 0}, before), ("No arbitrary extension past a junction"));
  std::vector<IRoadGraph::EdgeProjectionT> candidates{{before, geometry::MakePointWithAltitudeForTesting({0, 0})},
                                                      {after, geometry::MakePointWithAltitudeForTesting(camera)}};
  auto match = MatchRoad(camera, {-1, 0}, 5, candidates);
  TEST(match.has_value(), ("Connected fragments must not be treated as parallel roads"));
  TEST(AreConsecutiveRoadEdges(before, after), ());
  TEST(!AreConsecutiveRoadEdges(before, after.GetReverseEdge()), ());
  TEST(!AreConsecutiveRoadEdges(before, MakeEdge(2, {0, 0}, {0, -0.001})), ("Side road"));
  candidates.push_back({MakeEdge(3, {0.001, 0.00002}, {-0.001, 0.00002}),
                        geometry::MakePointWithAltitudeForTesting({camera.x, 0.00002})});
  TEST(!MatchRoad(camera, {-1, 0}, 5, candidates), ("A genuinely separate parallel road is still ambiguous"));
}
#endif

}  // namespace road_info_tests
