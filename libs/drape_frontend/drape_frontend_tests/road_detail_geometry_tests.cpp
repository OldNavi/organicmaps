#include "testing/testing.hpp"

#include "drape_frontend/road_detail_geometry.hpp"
#include "drape_frontend/road_junction_geometry.hpp"
#include "drape_frontend/road_label_occlusion.hpp"

#include "geometry/mercator.hpp"
#include "geometry/triangle2d.hpp"

namespace road_detail_geometry_tests
{
using Triangles = std::vector<m2::PointD>;

double Area(Triangles const & triangles)
{
  double area = 0;
  for (size_t i = 0; i < triangles.size(); i += 3)
    area += std::abs(CrossProduct(triangles[i + 1] - triangles[i], triangles[i + 2] - triangles[i])) / 2;
  return area;
}

bool Contains(Triangles const & triangles, m2::PointD const & point)
{
  for (size_t i = 0; i < triangles.size(); i += 3)
    if (m2::IsPointInsideTriangle(point, triangles[i], triangles[i + 1], triangles[i + 2]))
      return true;
  return false;
}

UNIT_TEST(RoadDetailGeometry_PhysicalWidth)
{
  auto const origin = mercator::FromLatLon(55.62333, 37.79305);
  df::RoadDetailGeometry geometry({origin, origin + m2::PointD(0.002, 0)});
  TEST(geometry.IsValid(), ());
  auto const triangles = geometry.Surface(18.25, mercator::Bounds::FullRect());
  TEST_EQUAL(triangles.size(), 6, ());
  m2::RectD bounds;
  for (auto const & point : triangles)
    bounds.Add(point);
  TEST_ALMOST_EQUAL_ABS(mercator::DistanceOnEarth({origin.x, bounds.minY()}, {origin.x, bounds.maxY()}), 18.25, 0.002,
                        ());
}

UNIT_TEST(RoadDetailGeometry_ClippingPreservesAreaAndDashPhase)
{
  auto const origin = mercator::FromLatLon(55.62, 37.79);
  auto const unit = 0.00001 / mercator::DistanceOnEarth(origin, origin + m2::PointD(0.00001, 0));
  df::RoadDetailGeometry geometry({origin, origin + m2::PointD(17 * unit, 0), origin + m2::PointD(60 * unit, 0)});
  m2::RectD const full(origin.x - unit, origin.y - 20 * unit, origin.x + 61 * unit, origin.y + 20 * unit);
  m2::RectD const left(full.minX(), full.minY(), origin.x + 14 * unit, full.maxY());
  m2::RectD const right(left.maxX(), full.minY(), full.maxX(), full.maxY());
  auto const surface = geometry.Surface(7, full);
  TEST_ALMOST_EQUAL_ABS(Area(geometry.Surface(7, left)) + Area(geometry.Surface(7, right)), Area(surface), 1e-14, ());
  feature::RoadDetails details;
  details.m_lanes.resize(2);
  auto const all = geometry.Markings(details, full);
  auto a = geometry.Markings(details, left);
  auto const b = geometry.Markings(details, right);
  a.insert(a.end(), b.begin(), b.end());
  TEST_ALMOST_EQUAL_ABS(Area(a), Area(all), 1e-14, ());
  for (double meters : {1.0, 4.0, 13.0, 14.5, 16.0, 25.0, 28.0, 37.0, 49.0})
  {
    auto const point = origin + m2::PointD(meters * unit, 0);
    TEST_EQUAL(Contains(a, point), Contains(all, point), (meters));
    TEST_EQUAL(Contains(all, point), std::fmod(meters, 12) < 3, (meters));
  }
  details.m_markings = false;
  TEST(geometry.Markings(details, full).empty(), ());
}

UNIT_TEST(RoadDetailGeometry_CurvesAndDegeneratePaths)
{
  auto const p = mercator::FromLatLon(55, 37);
  df::RoadDetailGeometry curved({p, p, p + m2::PointD(0.001, 0), p + m2::PointD(0.002, 0.0005)});
  TEST(curved.IsValid(), ());
  TEST_GREATER(Area(curved.Surface(12, mercator::Bounds::FullRect())), 0, ());
  TEST(!df::RoadDetailGeometry({p, p}).IsValid(), ());
  TEST(!df::RoadDetailGeometry({p, p + m2::PointD(0.001, 0), p}).IsValid(), ());
}
feature::RoadJunctionArm Arm(uint32_t id, bool forward, m2::PointD const & center, m2::PointD const & offset,
                             size_t lanes)
{
  feature::RoadJunctionArm arm;
  double const unit = 0.00001 / mercator::DistanceOnEarth(center, center + m2::PointD(0.00001, 0));
  arm.m_featureId = id;
  arm.m_forward = forward;
  arm.m_oneWay = true;
  arm.m_markings = true;
  arm.m_position = center + offset * unit;
  arm.m_directionAway = offset.Normalize();
  arm.m_nodeDirectionAway = arm.m_directionAway;
  arm.m_cutDistance = offset.Length();
  arm.m_normalAwayPerMeter = {-arm.m_directionAway.y * unit, arm.m_directionAway.x * unit};
  arm.m_widthsCm.assign(lanes, 350);
  arm.m_turns.assign(lanes, 0);
  return arm;
}

UNIT_TEST(RoadJunctionGeometry_MergeOnePlusOneIntoTwoAndSplit)
{
  auto const p = mercator::FromLatLon(55.62, 37.79);
  feature::RoadJunction junction;
  junction.m_center = p;
  junction.m_arms = {Arm(10, false, p, {-25, 7}, 1), Arm(11, false, p, {-25, -7}, 1), Arm(12, true, p, {25, 0}, 2)};
  auto connections = df::BuildRoadLaneConnections(junction);
  TEST_EQUAL(connections.size(), 2, ());
  TEST_EQUAL(connections[0].m_inArm, 0, ());
  TEST_EQUAL(connections[0].m_outLane, 0, ());
  TEST_EQUAL(connections[1].m_inArm, 1, ());
  TEST_EQUAL(connections[1].m_outLane, 1, ());
  TEST(Contains(df::BuildRoadJunctionSurface(junction, mercator::Bounds::FullRect()), p), ());
  TEST(!df::BuildRoadJunctionMarkings(junction, mercator::Bounds::FullRect()).empty(), ());
  for (auto & arm : junction.m_arms)
    arm.m_forward = !arm.m_forward;
  connections = df::BuildRoadLaneConnections(junction);
  TEST_EQUAL(connections.size(), 2, ());
  TEST_EQUAL(connections[0].m_inLane, 0, ());
  TEST_EQUAL(connections[0].m_outArm, 1, ("Lane order follows travel direction"));
  TEST_EQUAL(connections[1].m_outArm, 0, ());
}

UNIT_TEST(RoadJunctionGeometry_CloseExitDoesNotInterleaveMainLanes)
{
  auto const p = mercator::FromLatLon(55.707278, 37.835008);
  feature::RoadJunction junction;
  junction.m_center = p;
  junction.m_arms = {Arm(10, false, p, {-30, 0}, 7), Arm(11, true, p, {30, 0}, 6), Arm(12, true, p, {30, -5}, 1)};
  junction.m_arms[0].m_turns.assign(7, feature::RoadDetails::Through);
  junction.m_arms[0].m_turns.back() = feature::RoadDetails::Right;
  auto const lanes = df::BuildRoadLaneConnections(junction);
  TEST_EQUAL(lanes.size(), 7, ());
  for (size_t i = 0; i < 6; ++i)
  {
    TEST_EQUAL(lanes[i].m_outArm, 1, (i));
    TEST_EQUAL(lanes[i].m_outLane, i, ());
  }
  TEST_EQUAL(lanes.back().m_outArm, 2, ());
  auto const mesh = df::BuildRoadJunctionMesh(junction, mercator::Bounds::FullRect());
  TEST(!mesh.m_markings.empty(), ("A valid exit must not erase every through-lane divider"));
  TEST(df::SubtractRoadTriangles(mesh.m_markings, mesh.m_surface).empty(), ());
}

UNIT_TEST(RoadJunctionGeometry_WidthTransitionHasNoCapsule)
{
  auto const p = mercator::FromLatLon(55.62, 37.79);
  double const unit = 0.00001 / mercator::DistanceOnEarth(p, p + m2::PointD(0.00001, 0));
  feature::RoadJunction junction;
  junction.m_center = p;
  junction.m_arms = {Arm(10, false, p, {-25, 0}, 1), Arm(11, true, p, {25, 0}, 2)};
  auto const surface = df::BuildRoadJunctionSurface(junction, mercator::Bounds::FullRect());
  for (auto const & vertex : surface)
    TEST_LESS_OR_EQUAL(std::abs(vertex.y - p.y), 3.501 * unit, ());
  for (double x : {-24.0, -10.0, 0.0, 10.0, 24.0})
    TEST(Contains(surface, p + m2::PointD(x * unit, 0)), (x));
  TEST(!Contains(surface, p + m2::PointD(-24 * unit, 2.5 * unit)), ("No wide round cap on the narrow side"));
  TEST(!df::BuildRoadJunctionMarkings(junction, mercator::Bounds::FullRect()).empty(), ());
}

UNIT_TEST(RoadJunctionGeometry_InternalNodeReplacesOnlyLocalRoadSurface)
{
  auto const p = mercator::FromLatLon(55.62, 37.79);
  double const unit = 0.00001 / mercator::DistanceOnEarth(p, p + m2::PointD(0.00001, 0));
  feature::RoadJunction junction;
  junction.m_center = p;
  junction.m_arms = {Arm(10, false, p, {-10, 0}, 2), Arm(10, true, p, {10, 0}, 2)};
  for (auto & arm : junction.m_arms)
  {
    arm.m_nodeDistance = 40;
    arm.m_cutDistance = 10;
  }
  df::RoadDetailGeometry road({p - m2::PointD(40 * unit, 0), p, p + m2::PointD(40 * unit, 0)},
                              {{&junction, 0}, {&junction, 1}});
  auto const surface = road.Surface(7, mercator::Bounds::FullRect());
  TEST(!Contains(surface, p), ("The shared junction owns the central part exactly once"));
  TEST(Contains(surface, p - m2::PointD(20 * unit, 0)), ());
  TEST(Contains(surface, p + m2::PointD(20 * unit, 0)), ());
  TEST(Contains(df::BuildRoadJunctionSurface(junction, mercator::Bounds::FullRect()), p), ());
}

UNIT_TEST(RoadJunctionGeometry_TurnRestrictionsAndUnknownDirection)
{
  auto const p = mercator::FromLatLon(55.62, 37.79);
  feature::RoadJunction junction;
  junction.m_center = p;
  junction.m_arms = {Arm(10, false, p, {-20, 0}, 1), Arm(11, true, p, {20, 0}, 1)};
  junction.m_arms[0].m_turns[0] = feature::RoadDetails::Right;
  TEST(df::BuildRoadLaneConnections(junction).empty(), ());
  junction.m_arms[0].m_turns[0] = feature::RoadDetails::Through;
  TEST_EQUAL(df::BuildRoadLaneConnections(junction).size(), 1, ());
  junction.m_arms[1].m_oneWay = false;
  TEST(df::BuildRoadLaneConnections(junction).empty(), ());
}
UNIT_TEST(RoadDetailGeometry_ArrowsFollowLaneTurns)
{
  auto const p = mercator::FromLatLon(55.62, 37.79);
  double const unit = 0.00001 / mercator::DistanceOnEarth(p, p + m2::PointD(0.00001, 0));
  df::RoadDetailGeometry road({p - m2::PointD(40 * unit, 0), p + m2::PointD(40 * unit, 0)});
  feature::RoadDetails details;
  details.m_oneWay = true;
  details.m_lanes.resize(3);
  details.m_lanes[0].m_turns = feature::RoadDetails::Through;
  details.m_lanes[1].m_turns = feature::RoadDetails::Through;
  details.m_lanes[2].m_turns = feature::RoadDetails::Right;
  auto const arrows = road.Arrows(details, mercator::Bounds::FullRect());
  TEST(!arrows.empty(), ());
  TEST(Contains(arrows, p + m2::PointD(-18.8, 3.5) * unit), ());
  TEST(Contains(arrows, p + m2::PointD(-18.8, 0) * unit), ());
  TEST(!Contains(arrows, p + m2::PointD(-18.8, -3.5) * unit), ("Right-turn lane must not get a through arrow"));
  TEST(Contains(arrows, p + m2::PointD(-19.8, -4.6) * unit), ());
  for (auto const & point : arrows)
    TEST_LESS(std::abs(point.y - p.y), details.WidthMeters() * unit / 2, ());
}

UNIT_TEST(RoadDetailGeometry_ArrowsAvoidJunctions)
{
  auto const p = mercator::FromLatLon(55.62, 37.79);
  double const unit = 0.00001 / mercator::DistanceOnEarth(p, p + m2::PointD(0.00001, 0));
  feature::RoadJunction junction;
  junction.m_center = p;
  junction.m_arms = {Arm(10, false, p, {-10, 0}, 2), Arm(10, true, p, {10, 0}, 2)};
  for (auto & arm : junction.m_arms)
  {
    arm.m_nodeDistance = 20;
    arm.m_cutDistance = 10;
  }
  df::RoadDetailGeometry road({p - m2::PointD(20 * unit, 0), p, p + m2::PointD(20 * unit, 0)},
                              {{&junction, 0}, {&junction, 1}});
  feature::RoadDetails details;
  details.m_oneWay = true;
  details.m_lanes.resize(2);
  TEST(road.Arrows(details, mercator::Bounds::FullRect()).empty(), ());
}
UNIT_TEST(RoadJunctionGeometry_ContinuationMatchesSingleRoad)
{
  auto const p = mercator::FromLatLon(55.62, 37.79);
  double const unit = 0.00001 / mercator::DistanceOnEarth(p, p + m2::PointD(0.00001, 0));
  for (double angle : {-0.25, 0.0, 0.25})
  {
    auto const a = p - m2::PointD(40 * unit, 0);
    auto const b = p + m2::PointD(std::cos(angle), std::sin(angle)) * (40 * unit);
    feature::RoadJunction junction;
    junction.m_center = p;
    junction.m_continuationA = 0;
    junction.m_continuationB = 1;
    junction.m_arms = {Arm(10, false, p, {-10, 0}, 5),
                       Arm(11, true, p, m2::PointD(std::cos(angle), std::sin(angle)) * 10, 5)};
    for (auto & arm : junction.m_arms)
    {
      arm.m_featureEndpoint = true;
      arm.m_nodeDirectionAway = arm.m_directionAway;
    }
    df::RoadDetailGeometry first({a, p}, {{&junction, 0}});
    df::RoadDetailGeometry second({p, b}, {{&junction, 1}});
    auto surface = first.Surface(18.25, mercator::Bounds::FullRect());
    auto const tail = second.Surface(18.25, mercator::Bounds::FullRect());
    surface.insert(surface.end(), tail.begin(), tail.end());
    auto const continuous = df::RoadDetailGeometry({a, p, b}).Surface(18.25, mercator::Bounds::FullRect());
    for (int x = -12; x <= 12; ++x)
      for (int y = -12; y <= 12; ++y)
      {
        auto const sample = p + m2::PointD(x * unit, y * unit);
        TEST_EQUAL(Contains(surface, sample), Contains(continuous, sample), (angle, x, y));
      }
    TEST(df::BuildRoadJunctionSurface(junction, mercator::Bounds::FullRect()).empty(), ());
  }
}

UNIT_TEST(RoadJunctionGeometry_TaperMarkingsStayInsideSurface)
{
  auto const p = mercator::FromLatLon(55.62, 37.79);
  feature::RoadJunction junction;
  junction.m_center = p;
  for (bool widening : {false, true})
  {
    junction.m_arms = {Arm(10, false, p, {-25, 0}, widening ? 1 : 2), Arm(11, true, p, {25, 0}, widening ? 2 : 1)};
    auto const surface = df::BuildRoadJunctionSurface(junction, mercator::Bounds::FullRect());
    auto const markings = df::BuildRoadJunctionMarkings(junction, mercator::Bounds::FullRect());
    TEST(!markings.empty(), ());
    for (size_t i = 0; i < markings.size(); i += 3)
    {
      auto const center = (markings[i] + markings[i + 1] + markings[i + 2]) / 3;
      TEST(Contains(surface, center + (p - center) * 1e-8), (center));
    }
  }
}

UNIT_TEST(RoadJunctionGeometry_RecordedRoundaboutKeepsItsContour)
{
  // OpenStreetMap way 720574621, the regression example at the entrance to the market.
  std::vector<m2::PointD> points;
  points.push_back(mercator::FromLatLon(55.6198383, 37.7837873));
  points.push_back(mercator::FromLatLon(55.6198526, 37.7837231));
  points.push_back(mercator::FromLatLon(55.6198557, 37.7836543));
  points.push_back(mercator::FromLatLon(55.6198474, 37.7835868));
  points.push_back(mercator::FromLatLon(55.6198283, 37.7835266));
  points.push_back(mercator::FromLatLon(55.6198000, 37.7834789));
  points.push_back(mercator::FromLatLon(55.6197651, 37.7834480));
  points.push_back(mercator::FromLatLon(55.6197305, 37.7834366));
  points.push_back(mercator::FromLatLon(55.6196953, 37.7834419));
  points.push_back(mercator::FromLatLon(55.6196622, 37.7834635));
  points.push_back(mercator::FromLatLon(55.6196335, 37.7834998));
  points.push_back(mercator::FromLatLon(55.6196121, 37.7835460));
  points.push_back(mercator::FromLatLon(55.6195980, 37.7836004));
  points.push_back(mercator::FromLatLon(55.6195921, 37.7836594));
  points.push_back(mercator::FromLatLon(55.6195949, 37.7837190));
  points.push_back(mercator::FromLatLon(55.6196061, 37.7837754));
  points.push_back(mercator::FromLatLon(55.6196250, 37.7838250));
  points.push_back(mercator::FromLatLon(55.6196504, 37.7838644));
  points.push_back(mercator::FromLatLon(55.6196808, 37.7838911));
  points.push_back(mercator::FromLatLon(55.6197138, 37.7839033));
  points.push_back(mercator::FromLatLon(55.6197275, 37.7839040));
  points.push_back(mercator::FromLatLon(55.6197411, 37.7839021));
  points.push_back(mercator::FromLatLon(55.6197704, 37.7838891));
  points.push_back(mercator::FromLatLon(55.6197972, 37.7838648));
  points.push_back(mercator::FromLatLon(55.6198203, 37.7838303));
  points.push_back(mercator::FromLatLon(55.6198383, 37.7837873));
  m2::MetricPolyline metric(points, true);
  auto const p = points[7];
  double const distance = metric.ProjectDistance(p, metric.Length() * 7 / (points.size() - 1));
  feature::RoadJunction junction;
  junction.m_center = p;
  junction.m_continuationA = 0;
  junction.m_continuationB = 1;
  junction.m_arms = {Arm(10, false, p, {-8, 0}, 1), Arm(10, true, p, {8, 0}, 1), Arm(11, false, p, {-12, 0}, 1)};
  for (size_t i = 0; i < 2; ++i)
  {
    auto & arm = junction.m_arms[i];
    arm.m_nodeDistance = distance;
    arm.m_cutDistance = 8;
    auto const frame = metric.Sample(distance + (arm.m_forward ? 8 : -8));
    arm.m_position = frame.m_position;
    arm.m_directionAway = frame.m_tangent * (arm.m_forward ? 1 : -1);
    arm.m_nodeDirectionAway = metric.Sample(distance).m_tangent * (arm.m_forward ? 1 : -1);
    arm.m_normalAwayPerMeter = frame.m_leftPerMeter * (arm.m_forward ? 1 : -1);
  }
  df::RoadDetailGeometry before(points, {}, true);
  df::RoadDetailGeometry withApproach(points, {{&junction, 0}, {&junction, 1}}, true);
  TEST_EQUAL(before.Surface(3.5, mercator::Bounds::FullRect()), withApproach.Surface(3.5, mercator::Bounds::FullRect()),
             ());
  auto ring = std::make_shared<df::RoadDetailGeometry>(
      points, feature::RoadJunctions::Links{{&junction, 0}, {&junction, 1}}, true);
  auto approach = std::make_shared<df::RoadDetailGeometry>(std::vector<m2::PointD>{junction.m_arms[2].m_position, p},
                                                           feature::RoadJunctions::Links{{&junction, 2}});
  auto const fillets =
      df::BuildRoadJunctionFillets(junction, 11, [&](uint32_t id) -> std::shared_ptr<df::RoadDetailGeometry const>
  { return id == 10 ? ring : approach; }, mercator::Bounds::FullRect());
  TEST(!fillets.empty(), ());
  auto const island = mercator::FromLatLon(55.619726, 37.783670);
  TEST(!Contains(fillets, island), ("An approach must not fill the roundabout island"));
  for (auto const & vertex : fillets)
    TEST_GREATER(mercator::DistanceOnEarth(vertex, island), 10.0, ());
  TEST_GREATER(metric.Points().size(), points.size(), ());
}
UNIT_TEST(RoadJunctionGeometry_SideRoadFilletsPreserveThroughCarriageway)
{
  auto const p = mercator::FromLatLon(55.62, 37.79);
  double const unit = 0.00001 / mercator::DistanceOnEarth(p, p + m2::PointD(0.00001, 0));
  feature::RoadJunction junction;
  junction.m_center = p;
  junction.m_continuationA = 0;
  junction.m_continuationB = 1;
  junction.m_arms = {Arm(10, false, p, {-20, 0}, 2), Arm(10, true, p, {20, 0}, 2), Arm(11, true, p, {0, 20}, 1)};
  auto through = std::make_shared<df::RoadDetailGeometry>(
      std::vector<m2::PointD>{p - m2::PointD(20, 0) * unit, p, p + m2::PointD(20, 0) * unit});
  auto approach = std::make_shared<df::RoadDetailGeometry>(std::vector<m2::PointD>{p, p + m2::PointD(0, 20) * unit});
  auto const surface =
      df::BuildRoadJunctionFillets(junction, 11, [&](uint32_t id) -> std::shared_ptr<df::RoadDetailGeometry const>
  { return id == 10 ? through : approach; }, mercator::Bounds::FullRect());
  TEST(!surface.empty(), ());
  for (double x : {-2.25, 2.25})
    TEST(Contains(surface, p + m2::PointD(x, 4.0) * unit), ("Round the two sharp approach corners"));
  TEST(!Contains(surface, p + m2::PointD(0, -4.0) * unit), ("Keep the opposite road edge unchanged"));
  TEST(!Contains(surface, p + m2::PointD(5, 6) * unit), ("Do not fill the whole junction envelope"));
  TEST(df::BuildRoadJunctionMarkings(junction, mercator::Bounds::FullRect()).empty(), ());
}

UNIT_TEST(RoadJunctionGeometry_SharedCutsSurviveSimplifiedMwmGeometry)
{
  auto const p = mercator::FromLatLon(55.62, 37.79);
  double const unit = 0.00001 / mercator::DistanceOnEarth(p, p + m2::PointD(0.00001, 0));
  feature::RoadJunction junction;
  junction.m_center = p;
  junction.m_arms = {Arm(10, false, p, {-20, 0.3}, 5), Arm(11, true, p, {20, -0.3}, 4)};
  for (auto & arm : junction.m_arms)
  {
    arm.m_featureEndpoint = true;
    arm.m_normalAwayPerMeter = m2::PointD(0.2, 1.0) * (unit * (arm.m_forward ? 1 : -1));
  }
  // The best available MWM geometry removed the nearby shallow bends used by the generator.
  df::RoadDetailGeometry first({p - m2::PointD(60, 0) * unit, p}, {{&junction, 0}});
  df::RoadDetailGeometry second({p, p + m2::PointD(60, 0) * unit}, {{&junction, 1}});
  auto surface = df::BuildRoadJunctionSurface(junction, mercator::Bounds::FullRect());
  for (auto const & triangles :
       {first.Surface(17.5, mercator::Bounds::FullRect()), second.Surface(14.0, mercator::Bounds::FullRect())})
    surface.insert(surface.end(), triangles.begin(), triangles.end());
  for (auto const & arm : junction.m_arms)
    for (double fraction : {-0.98, -0.7, 0.0, 0.7, 0.98})
      for (double side : {-0.01, 0.01})
      {
        auto const point = arm.m_position + arm.m_normalAwayPerMeter * (arm.WidthMeters() * fraction / 2) +
                           arm.m_directionAway * (side * unit);
        TEST(Contains(surface, point), (arm.m_featureId, fraction, side));
      }
}

UNIT_TEST(RoadJunctionGeometry_RecordedBridgeDeckDoesNotCoverApproach)
{
  // OpenStreetMap bridge relation 7657798 overlaps its layer=0 approaches at both carriageways.
  {
    feature::RoadJunction junction;
    junction.m_center = {37.795321399999999, 67.229967299999998};
    junction.m_continuationA = 0;
    junction.m_continuationB = 1;
    junction.m_arms.resize(2);
    junction.m_arms[0].m_featureId = 16;
    junction.m_arms[0].m_forward = false;
    junction.m_arms[0].m_featureEndpoint = true;
    junction.m_arms[0].m_nodeDirectionAway = {-0.71910406389085957, -0.69490239983443036};
    junction.m_arms[0].m_nodeDistance = 317.61930646583392;
    junction.m_arms[0].m_widthsCm = {350, 350, 375, 375, 375};
    junction.m_arms[1].m_featureId = 18;
    junction.m_arms[1].m_forward = true;
    junction.m_arms[1].m_featureEndpoint = true;
    junction.m_arms[1].m_nodeDirectionAway = {0.68332158114293406, 0.73011753625311626};
    junction.m_arms[1].m_nodeDistance = 0;
    junction.m_arms[1].m_widthsCm = {350, 350, 375, 375, 375};
    auto road0 = std::make_shared<df::RoadDetailGeometry>(
        std::vector<m2::PointD>{{37.791688276765399, 67.226456755596814}, {37.795322670007664, 67.229967767223485}},
        feature::RoadJunctions::Links{{&junction, 0}});
    auto road1 =
        std::make_shared<df::RoadDetailGeometry>(std::vector<m2::PointD>{{37.795322670007664, 67.229967767223485},
                                                                         {37.796272172006013, 67.230984324447689},
                                                                         {37.79686494020271, 67.23164414787027},
                                                                         {37.797514034789174, 67.232395166400039},
                                                                         {37.798101438567812, 67.233135456093663},
                                                                         {37.798401845979697, 67.233500236522417},
                                                                         {37.798825635007205, 67.234031313911316},
                                                                         {37.799292339379292, 67.234583848972505},
                                                                         {37.799445225294278, 67.234766239186882}},
                                                 feature::RoadJunctions::Links{{&junction, 1}});
    auto const patch =
        df::BuildElevatedRoadConnection(junction, [&](uint32_t id) -> std::shared_ptr<df::RoadDetailGeometry const>
    { return id == 16 ? road0 : road1; }, mercator::Bounds::FullRect(), false);
    TEST(!patch.empty(), ());
    TEST(Contains(patch, m2::PointD{37.79524736196489, 67.230041694470017}),
         ("The deck must not erase this part of the approach"));
    for (auto const & vertex : patch)
      TEST_LESS(mercator::DistanceOnEarth(vertex, junction.m_center), 14.0, ("Keep promotion local to the seam"));
  }
  {
    feature::RoadJunction junction;
    junction.m_center = {37.794742200000002, 67.230421899999996};
    junction.m_continuationA = 0;
    junction.m_continuationB = 1;
    junction.m_arms.resize(2);
    junction.m_arms[0].m_featureId = 17;
    junction.m_arms[0].m_forward = true;
    junction.m_arms[0].m_featureEndpoint = true;
    junction.m_arms[0].m_nodeDirectionAway = {-0.71497511599421126, -0.69914990060005322};
    junction.m_arms[0].m_nodeDistance = 0;
    junction.m_arms[0].m_widthsCm = {350, 350, 375, 375, 375};
    junction.m_arms[1].m_featureId = 19;
    junction.m_arms[1].m_forward = false;
    junction.m_arms[1].m_featureEndpoint = true;
    junction.m_arms[1].m_nodeDirectionAway = {0.7251261939046586, 0.68861600541473289};
    junction.m_arms[1].m_nodeDistance = 402.51788190923037;
    junction.m_arms[1].m_widthsCm = {350, 350, 375, 375, 375};
    auto road0 = std::make_shared<df::RoadDetailGeometry>(
        std::vector<m2::PointD>{{37.794743312856127, 67.230421060550356}, {37.791218890184297, 67.226977104149597}},
        feature::RoadJunctions::Links{{&junction, 0}});
    auto road1 =
        std::make_shared<df::RoadDetailGeometry>(std::vector<m2::PointD>{{37.799190415435959, 67.235023731254216},
                                                                         {37.798557414103726, 67.234326356905143},
                                                                         {37.798235549019552, 67.233977669730621},
                                                                         {37.797039283789985, 67.232682162766764},
                                                                         {37.796242667706622, 67.231845313547893},
                                                                         {37.794743312856127, 67.230421060550356}},
                                                 feature::RoadJunctions::Links{{&junction, 1}});
    auto const patch =
        df::BuildElevatedRoadConnection(junction, [&](uint32_t id) -> std::shared_ptr<df::RoadDetailGeometry const>
    { return id == 17 ? road0 : road1; }, mercator::Bounds::FullRect(), false);
    TEST(!patch.empty(), ());
    TEST(Contains(patch, m2::PointD{37.794846375942832, 67.230321096311272}),
         ("The deck must not erase this part of the approach"));
    TEST(Contains(patch, m2::PointD{37.794809996817236, 67.230355183838398}),
         ("The deck must not erase this part of the approach"));
    for (auto const & vertex : patch)
      TEST_LESS(mercator::DistanceOnEarth(vertex, junction.m_center), 14.0, ("Keep promotion local to the seam"));
  }
}
UNIT_TEST(RoadLabels_UpperCarriagewaySplitsOnlyLowerLayer)
{
  auto const p = mercator::FromLatLon(55.62, 37.79);
  double const unit = 0.00001 / mercator::DistanceOnEarth(p, p + m2::PointD(0.00001, 0));
  std::vector<m2::SharedSpline> const lower{
      m2::SharedSpline({p - m2::PointD(30, 0) * unit, p + m2::PointD(30, 0) * unit})};
  auto upper = std::make_shared<df::RoadDetailGeometry>(
      std::vector<m2::PointD>{p - m2::PointD(0, 30) * unit, p + m2::PointD(0, 30) * unit});
  df::RoadLabelOcclusion labels;
  labels.Add(1, 7.0, upper);
  auto const split = labels.Clip(lower, 0, 1.0, mercator::Bounds::FullRect());
  TEST_EQUAL(split.size(), 2, ());
  TEST_ALMOST_EQUAL_ABS((split[0]->GetPath().back().x - p.x) / unit, -4.5, 0.002, ());
  TEST_ALMOST_EQUAL_ABS((split[1]->GetPath().front().x - p.x) / unit, 4.5, 0.002, ());
  for (int layer : {1, 2})
  {
    auto const same = labels.Clip(lower, layer, 1.0, mercator::Bounds::FullRect());
    TEST_EQUAL(same.size(), 1, ());
    TEST_EQUAL(same.front().Get(), lower.front().Get(), ("Same and higher layers keep their label path"));
  }
}

UNIT_TEST(RoadLabels_JunctionCutsDoNotExposeLowerRoadNames)
{
  auto const p = mercator::FromLatLon(55.70799, 37.8352);
  double const unit = 0.00001 / mercator::DistanceOnEarth(p, p + m2::PointD(0.00001, 0));
  feature::RoadJunction start, end;
  start.m_center = p + m2::PointD(0, -35) * unit;
  end.m_center = p + m2::PointD(0, 35) * unit;
  feature::RoadJunctionArm arm;
  arm.m_featureId = 1;
  arm.m_featureEndpoint = true;
  arm.m_forward = true;
  arm.m_cutDistance = 28;
  arm.m_position = p + m2::PointD(0, -7) * unit;
  arm.m_directionAway = arm.m_nodeDirectionAway = {0, 1};
  arm.m_normalAwayPerMeter = {-unit, 0};
  arm.m_widthsCm = {350, 350, 350, 350, 350, 350};
  start.m_arms.push_back(arm);
  arm.m_forward = false;
  arm.m_nodeDistance = 70;
  arm.m_position = p + m2::PointD(0, 7) * unit;
  arm.m_directionAway = arm.m_nodeDirectionAway = {0, -1};
  arm.m_normalAwayPerMeter = {unit, 0};
  end.m_arms.push_back(arm);
  auto upper = std::make_shared<df::RoadDetailGeometry>(std::vector<m2::PointD>{start.m_center, end.m_center},
                                                        feature::RoadJunctions::Links{{&start, 0}, {&end, 0}});
  df::RoadLabelOcclusion labels;
  labels.Add(1, 21, upper);
  // The short MKAD bridge reserves 40% at each end for separate junction surfaces.
  // The avenue crosses these reserved spans, not the remaining central ribbon.
  for (double y : {-20.0, 20.0})
  {
    std::vector<m2::SharedSpline> lower{
        m2::SharedSpline({p + m2::PointD(-50, y) * unit, p + m2::PointD(50, y) * unit})};
    auto const clipped = labels.Clip(lower, 0, 1, mercator::Bounds::FullRect());
    TEST_EQUAL(clipped.size(), 2, (y));
    TEST_LESS(clipped[0]->GetPath().back().x, p.x - 10 * unit, ());
    TEST_GREATER(clipped[1]->GetPath().front().x, p.x + 10 * unit, ());
    TEST_EQUAL(labels.Clip(lower, 1, 1, mercator::Bounds::FullRect()).size(), 1, ());
  }
}

UNIT_TEST(RoadLabels_AllowForGlyphHeightNearParallelUpperRoad)
{
  auto const p = mercator::FromLatLon(55.62, 37.79);
  double const unit = 0.00001 / mercator::DistanceOnEarth(p, p + m2::PointD(0.00001, 0));
  std::vector<m2::SharedSpline> const lower{
      m2::SharedSpline({p + m2::PointD(-20, 1.5) * unit, p + m2::PointD(20, 1.5) * unit})};
  df::RoadLabelOcclusion labels;
  labels.Add(1, 4.0,
             std::make_shared<df::RoadDetailGeometry>(
                 std::vector<m2::PointD>{p + m2::PointD(-30, 4) * unit, p + m2::PointD(30, 4) * unit}));
  TEST_EQUAL(labels.Clip(lower, 0, 0, mercator::Bounds::FullRect()).size(), 1, ());
  TEST(labels.Clip(lower, 0, 1, mercator::Bounds::FullRect()).empty(), ());
}
}  // namespace road_detail_geometry_tests

namespace road_detail_geometry_tests
{
UNIT_TEST(RoadJunctionGeometry_BranchSurfaceAndPaintStopAtThroughCarriageway)
{
  auto const p = mercator::FromLatLon(55.65, 37.83);
  double const unit = 0.00001 / mercator::DistanceOnEarth(p, p + m2::PointD(0.00001, 0));
  feature::RoadJunction junction;
  junction.m_center = p;
  junction.m_arms = {Arm(1, false, p, {-30, 0}, 3), Arm(2, true, p, {30, 0}, 3), Arm(3, false, p, {-25, -25}, 2)};
  junction.m_continuationA = 0;
  junction.m_continuationB = 1;
  std::map<uint32_t, std::shared_ptr<df::RoadDetailGeometry const>> roads;
  roads[1] = std::make_shared<df::RoadDetailGeometry>(std::vector<m2::PointD>{p - m2::PointD(100 * unit, 0), p});
  roads[2] = std::make_shared<df::RoadDetailGeometry>(std::vector<m2::PointD>{p, p + m2::PointD(100 * unit, 0)});
  auto const clip = mercator::Bounds::FullRect();
  auto const getter = [&](uint32_t id) { return roads.at(id); };
  auto const mask = df::BuildRoadBranchMask(junction, 3, getter, clip);
  TEST(!mask.empty(), ());
  TEST(df::BuildRoadBranchMask(junction, 1, getter, clip).empty(), ());
  df::RoadDetailGeometry branch({p - m2::PointD(25 * unit, 25 * unit), p});
  auto const surface = branch.Surface(7, clip);
  auto const trimmed = df::SubtractRoadTriangles(surface, mask);
  TEST_LESS(Area(trimmed), Area(surface), ());
  TEST(!Contains(trimmed, p - m2::PointD(2 * unit, 2 * unit)), ("No colored branch tip on the main road"));
  TEST(Contains(trimmed, p - m2::PointD(15 * unit, 15 * unit)), ("The approach is retained"));
  feature::RoadDetails details;
  details.m_lanes.resize(2);
  auto const markings = df::SubtractRoadTriangles(branch.Markings(details, clip), mask);
  for (auto const & point : markings)
    TEST_LESS_OR_EQUAL(point.y, p.y - 5.249 * unit, ("Paint must stop with its surface"));
  TEST_ALMOST_EQUAL_ABS(Area(df::SubtractRoadTriangles(trimmed, mask)), Area(trimmed), 1e-15, ());

  junction.m_arms = {Arm(1, false, p, {-10.5, 0}, 2), Arm(2, true, p, {10.5, 0}, 2),
                     Arm(3, false, p, {-10.0, -2.7}, 1)};
  auto const acuteMask = df::BuildRoadBranchMask(junction, 3, getter, clip);
  df::RoadDetailGeometry acuteBranch({p - m2::PointD(30 * unit, 8 * unit), p});
  auto const acuteSurface = acuteBranch.Surface(3.5, clip);
  auto const overlap = p - m2::PointD(14 * unit, 3 * unit);
  TEST(Contains(acuteSurface, overlap), ());
  TEST(!Contains(df::SubtractRoadTriangles(acuteSurface, acuteMask), overlap),
       ("An acute approach overlaps the main road outside the centerline cut distance"));

  junction.m_continuationA = junction.m_continuationB = 255;
  junction.m_ownerFeatureId = 1;
  auto const transitionMask = df::BuildRoadBranchMask(junction, 3, getter, clip);
  TEST(!transitionMask.empty(), ());
  TEST(!Contains(df::SubtractRoadTriangles(acuteSurface, transitionMask), overlap),
       ("A common transition surface also owns the overlap with the approach ribbon"));
  TEST(df::BuildRoadBranchMask(junction, 1, getter, clip).empty(), ());
}
}  // namespace road_detail_geometry_tests

namespace road_detail_geometry_tests
{
UNIT_TEST(RoadJunctionGeometry_ShortLinkTransitionStopsAtThroughRoad)
{
  auto const p = mercator::FromLatLon(55.653509, 37.836131);
  double const unit = 0.00001 / mercator::DistanceOnEarth(p, p + m2::PointD(0.00001, 0));
  auto const q = p - m2::PointD(0, 4 * unit);
  feature::RoadJunction junction;
  junction.m_center = p;
  junction.m_continuationA = 0;
  junction.m_continuationB = 1;
  junction.m_arms = {Arm(1, false, p, {-16, 0}, 3), Arm(2, true, p, {16, 0}, 3), Arm(3, true, p, {0, -1.6}, 1)};
  feature::RoadJunction transition;
  transition.m_center = q;
  transition.m_ownerFeatureId = 4;
  transition.m_arms = {Arm(3, false, q, {0, 1.6}, 1), Arm(4, true, q, {0, -16}, 3)};
  junction.m_arms[2].m_featureEndpoint = transition.m_arms[0].m_featureEndpoint = true;
  transition.m_arms[0].m_nodeDistance = 4;
  std::map<uint32_t, std::shared_ptr<df::RoadDetailGeometry const>> roads;
  roads[1] = std::make_shared<df::RoadDetailGeometry>(std::vector<m2::PointD>{p - m2::PointD(100 * unit, 0), p});
  roads[2] = std::make_shared<df::RoadDetailGeometry>(std::vector<m2::PointD>{p, p + m2::PointD(100 * unit, 0)});
  roads[3] = std::make_shared<df::RoadDetailGeometry>(std::vector<m2::PointD>{p, q},
                                                      feature::RoadJunctions::Links{{&junction, 2}, {&transition, 0}});
  roads[4] = std::make_shared<df::RoadDetailGeometry>(std::vector<m2::PointD>{q, q - m2::PointD(0, 100 * unit)});
  auto const getter = [&](uint32_t id) { return roads.at(id); };
  auto const clip = mercator::Bounds::FullRect();
  auto const mesh = df::BuildRoadJunctionMesh(transition, clip);
  TEST(Contains(mesh.m_surface, q), ("The transition overlaps the through road"));
  auto const mask = df::BuildRoadBranchMask(transition, 4, getter, clip);
  TEST(!mask.empty(), ("The wide transition owner inherits clipping across the short link"));
  auto const surface = df::SubtractRoadTriangles(mesh.m_surface, mask);
  TEST(!Contains(surface, q), ("No tongue on the main carriageway"));
  TEST(Contains(surface, q - m2::PointD(0, 10 * unit)), ("The widening outside the main road remains"));
  for (auto const & point : df::SubtractRoadTriangles(mesh.m_markings, mask))
    TEST_LESS_OR_EQUAL(point.y, p.y - 5.249 * unit, ("Paint follows the same boundary"));
}

UNIT_TEST(RoadJunctionGeometry_RecordedBesedinskoeMergeKeepsNarrowCutPlane)
{
  feature::RoadJunction junction;
  junction.m_center = {37.7806841, 67.2196877};
  {
    feature::RoadJunctionArm arm;
    arm.m_featureId = 4090;
    arm.m_forward = true;
    arm.m_featureEndpoint = true;
    arm.m_oneWay = true;
    arm.m_markings = false;
    arm.m_nodeDistance = 0;
    arm.m_cutDistance = 10.5;
    arm.m_position = {37.78055076237901, 67.21978830929584};
    arm.m_directionAway = {-0.8167843336331629, 0.576943110134292};
    arm.m_nodeDirectionAway = {-0.7982549823830181, 0.602319668532157};
    arm.m_normalAwayPerMeter = {-9.182672103566211e-06, -1.3000003957647343e-05};
    arm.m_widthsCm = {350};
    arm.m_turns.resize(arm.m_widthsCm.size());
    junction.m_arms.push_back(arm);
  }
  {
    feature::RoadJunctionArm arm;
    arm.m_featureId = 4082;
    arm.m_forward = true;
    arm.m_featureEndpoint = true;
    arm.m_oneWay = false;
    arm.m_markings = true;
    arm.m_nodeDistance = 0;
    arm.m_cutDistance = 10.5;
    arm.m_position = {37.78081990984955, 67.2195904544716};
    arm.m_directionAway = {0.8114321324209479, -0.5844466566546457};
    arm.m_nodeDirectionAway = {0.8130576137638387, -0.5821832329264152};
    arm.m_normalAwayPerMeter = {9.297516734882598e-06, 1.2908455792336283e-05};
    arm.m_widthsCm = {350, 350};
    arm.m_turns.resize(arm.m_widthsCm.size());
    junction.m_arms.push_back(arm);
  }
  {
    feature::RoadJunctionArm arm;
    arm.m_featureId = 4091;
    arm.m_forward = false;
    arm.m_featureEndpoint = true;
    arm.m_oneWay = true;
    arm.m_markings = true;
    arm.m_nodeDistance = 96.37246405776253;
    arm.m_cutDistance = 10.5;
    arm.m_position = {37.7805318038943, 67.21975630785583};
    arm.m_directionAway = {-0.9156163534411978, 0.4020530976264745};
    arm.m_nodeDirectionAway = {-0.9117542806790705, 0.4107360851731814};
    arm.m_normalAwayPerMeter = {-6.396235786962392e-06, -1.4566479207802416e-05};
    arm.m_widthsCm = {350};
    arm.m_turns.resize(arm.m_widthsCm.size());
    junction.m_arms.push_back(arm);
  }
  auto const & narrow = junction.m_arms[0];
  auto const triangles = df::BuildRoadJunctionSurface(junction, mercator::Bounds::FullRect());
  double const unit =
      0.00001 / mercator::DistanceOnEarth(junction.m_center, junction.m_center + m2::PointD(0.00001, 0));
  for (double distance : {-0.3, 0.0, 0.3})
  {
    auto const point = narrow.m_position + narrow.m_directionAway * (distance * unit) -
                       narrow.m_normalAwayPerMeter * (narrow.WidthMeters() / 2 + 0.5);
    TEST(!Contains(triangles, point), ("No extra shoulder outside the one-lane cut plane", distance));
  }
}
}  // namespace road_detail_geometry_tests

namespace road_detail_geometry_tests
{
UNIT_TEST(RoadDetailGeometry_PlacementKeepsReferenceAtLaneBoundary)
{
  auto const p = mercator::FromLatLon(55.64, 37.82);
  double const unit = 0.00001 / mercator::DistanceOnEarth(p, p + m2::PointD(0.00001, 0));
  df::RoadDetailGeometry geometry({p, p + m2::PointD(100 * unit, 0)}, {}, false, -1.75, -1.75);
  auto const clip = mercator::Bounds::FullRect();
  auto const surface = geometry.Surface(10.5, clip);
  TEST(Contains(surface, p + m2::PointD(25 * unit, 3.4 * unit)), ());
  TEST(!Contains(surface, p + m2::PointD(25 * unit, 3.6 * unit)), ());
  TEST(Contains(surface, p + m2::PointD(25 * unit, -6.9 * unit)), ());
  TEST(!Contains(surface, p + m2::PointD(25 * unit, -7.1 * unit)), ());
  feature::RoadDetails details;
  details.m_lanes.resize(3);
  auto const paint = geometry.Markings(details, clip);
  TEST(Contains(paint, p + m2::PointD(25 * unit, 0)), ("right_of:1 puts the first divider on the OSM way"));
  TEST(Contains(paint, p + m2::PointD(25 * unit, -3.5 * unit)), ());
}

UNIT_TEST(RoadJunctionGeometry_ElevatedConnectionFollowsDeckFootprint)
{
  auto const p = mercator::FromLatLon(55.6876, 37.8297);
  double const unit = 0.00001 / mercator::DistanceOnEarth(p, p + m2::PointD(0.00001, 0));
  feature::RoadJunction junction;
  junction.m_center = p;
  junction.m_arms = {Arm(1, false, p, {-30, 0}, 3), Arm(2, true, p, {30, 0}, 3)};
  junction.m_continuationA = 0;
  junction.m_continuationB = 1;
  std::map<uint32_t, std::shared_ptr<df::RoadDetailGeometry const>> roads;
  roads[1] = std::make_shared<df::RoadDetailGeometry>(std::vector<m2::PointD>{p - m2::PointD(100 * unit, 0), p});
  roads[2] = std::make_shared<df::RoadDetailGeometry>(std::vector<m2::PointD>{p, p + m2::PointD(100 * unit, 0)});
  auto const a = p + m2::PointD(-15, -12) * unit, b = p + m2::PointD(20, -12) * unit;
  auto const c = p + m2::PointD(20, 12) * unit, d = p + m2::PointD(-15, 12) * unit;
  df::RoadDecks decks;
  decks.Add(1, {a, b, c, a, c, d});
  auto const clip = mercator::Bounds::FullRect();
  auto const mask = decks.Mask(junction, 1, clip);
  TEST(!mask.empty(), ());
  TEST(decks.Mask(junction, 2, clip).empty(), ());
  auto const surface =
      df::BuildElevatedRoadConnection(junction, [&](uint32_t id) { return roads.at(id); }, clip, false, mask);
  TEST(Contains(surface, p - m2::PointD(12 * unit, 0)), ("Deck overlap is not limited to a fixed five metres"));
  TEST(!Contains(surface, p - m2::PointD(18 * unit, 0)), ("Keep the approach's own layer outside the deck"));
}
}  // namespace road_detail_geometry_tests

namespace road_detail_geometry_tests
{
UNIT_TEST(RoadJunctionGeometry_RecordedTwoLaneYJunctionsHaveNoNeck)
{
  struct ArmFrame
  {
    m2::PointD position, tangent, normal;
    bool forward;
    uint8_t lanes;
  };
  struct Sample
  {
    m2::PointD center;
    std::array<ArmFrame, 3> arms;
  };
  std::array<Sample, 2> const samples = {{
      {{38.816548, 66.3455476},
       {{
           {{38.81660123067938, 66.34570373101648},
            {0.3231939352089779, 0.9463327534457079},
            {-1.4866675470173374e-05, 5.077304289833789e-06},
            false,
            2},
           {{38.81645404699007, 66.34541201977656},
            {-0.6469563168250094, -0.7625270645165442},
            {1.2036352673401546e-05, -1.0212089191257512e-05},
            true,
            1},
           {{38.81651785232708, 66.34538581787812},
            {-0.04273073237855327, -0.9990866251283682},
            {1.578666678070726e-05, -6.751925372528392e-07},
            false,
            1},
       }}},
      {{38.81412, 66.3449314},
       {{
           {{38.81405199793425, 66.34478117140407},
            {-0.3805311266829094, -0.9247681123532729},
            {1.4527865301078333e-05, -5.9780445253988185e-06},
            true,
            2},
           {{38.8142369916302, 66.34504759068489},
            {0.7869145748541811, 0.6170619514133595},
            {-9.755070050529036e-06, 1.2440253014956217e-05},
            true,
            1},
           {{38.81409729613034, 66.34509475570408},
            {-0.17652337212697977, 0.984296448786096},
            {-1.5463591087998626e-05, -2.773234880012694e-06},
            false,
            1},
       }}},
  }};
  for (auto const & sample : samples)
  {
    feature::RoadJunction junction;
    junction.m_center = sample.center;
    for (auto const & frame : sample.arms)
    {
      feature::RoadJunctionArm arm;
      arm.m_position = frame.position;
      arm.m_directionAway = arm.m_nodeDirectionAway = frame.tangent;
      arm.m_normalAwayPerMeter = frame.normal;
      arm.m_forward = frame.forward;
      arm.m_widthsCm.assign(frame.lanes, 350);
      arm.m_turns.assign(frame.lanes, 0);
      junction.m_arms.push_back(arm);
    }
    auto const surface = df::BuildRoadJunctionSurface(junction, mercator::Bounds::FullRect());
    auto const & wide = junction.m_arms[0];
    double const unit = 0.00001 / mercator::DistanceOnEarth(sample.center, sample.center + m2::PointD(0.00001, 0));
    auto const left = wide.m_normalAwayPerMeter.Normalize();
    for (double distance : {1.0, 3.0, 6.0, 9.0})
    {
      auto const center = wide.m_position - wide.m_directionAway * (distance * unit);
      double width = 0;
      for (int step = -200; step <= 200; ++step)
        if (Contains(surface, center + left * (step * 0.05 * unit)))
          width += 0.05;
      TEST_GREATER_OR_EQUAL(width, 6.8, ("Two 3.5 m lanes must not collapse to one", sample.center, distance));
    }
  }
}
}  // namespace road_detail_geometry_tests

namespace road_detail_geometry_tests
{
UNIT_TEST(RoadDetailGeometry_BusSymbolOnlyInDesignatedLane)
{
  auto const p = mercator::FromLatLon(55.7, 37.76);
  double const unit = 0.00001 / mercator::DistanceOnEarth(p, p + m2::PointD(0.00001, 0));
  df::RoadDetailGeometry geometry({p, p + m2::PointD(80 * unit, 0)});
  feature::RoadDetails details;
  details.m_oneWay = true;
  details.m_lanes.resize(3);
  auto const plain = geometry.Arrows(details, mercator::Bounds::FullRect());
  details.m_lanes[2].m_publicTransport = true;
  auto const marked = geometry.Arrows(details, mercator::Bounds::FullRect());
  auto const busWheel = p + m2::PointD(18.8 * unit, (-3.5 + 0.4) * unit);
  TEST(!Contains(plain, busWheel), ());
  TEST(Contains(marked, busWheel), ("The designated right lane has a bus pictogram"));
  TEST(!Contains(marked, p + m2::PointD(18.8 * unit, 0.4 * unit)), ("The ordinary lane retains its arrow"));
  details.m_lanes[2].m_turns = feature::RoadDetails::Right;
  auto const turn = geometry.Arrows(details, mercator::Bounds::FullRect());
  TEST(Contains(turn, busWheel - m2::PointD(6 * unit, 0)), ("Bus pictogram is separated from the turn arrow"));
}
}  // namespace road_detail_geometry_tests

namespace road_detail_geometry_tests
{
UNIT_TEST(RoadJunctionGeometry_RecordedThreeLaneExit)
{
  feature::RoadJunction junction;
  junction.m_center = {37.8220076, 67.2573026};
  {
    feature::RoadJunctionArm arm;
    arm.m_featureId = 15393;
    arm.m_forward = true;
    arm.m_featureEndpoint = true;
    arm.m_oneWay = true;
    arm.m_markings = true;
    arm.m_cutDistance = 15.75;
    arm.m_position = {37.82223070823623, 67.25718828670847};
    arm.m_directionAway = {0.8899812997897093, -0.4559970241400921};
    arm.m_nodeDirectionAway = {0.8899812997897093, -0.4559970241400921};
    arm.m_normalAwayPerMeter = {7.258011309168263e-06, 1.416565020572916e-05};
    arm.m_widthsCm = {350, 350};
    arm.m_turns.resize(arm.m_widthsCm.size());
    junction.m_arms.push_back(arm);
  }
  {
    feature::RoadJunctionArm arm;
    arm.m_featureId = 15390;
    arm.m_forward = false;
    arm.m_featureEndpoint = true;
    arm.m_oneWay = true;
    arm.m_markings = true;
    arm.m_cutDistance = 15.75;
    arm.m_position = {37.82177166579987, 67.25739180061761};
    arm.m_directionAway = {-0.8906061851319991, 0.45477535443846046};
    arm.m_nodeDirectionAway = {-0.8906061851319992, 0.4547753544384605};
    arm.m_normalAwayPerMeter = {-7.238587467020392e-06, -1.417563794262227e-05};
    arm.m_widthsCm = {350, 350, 350};
    arm.m_turns.resize(arm.m_widthsCm.size());
    junction.m_arms.push_back(arm);
  }
  {
    feature::RoadJunctionArm arm;
    arm.m_featureId = 388367;
    arm.m_forward = true;
    arm.m_featureEndpoint = true;
    arm.m_oneWay = true;
    arm.m_markings = false;
    arm.m_cutDistance = 15.75;
    arm.m_position = {37.822191327027305, 67.25713205466455};
    arm.m_directionAway = {0.7095430407041461, -0.704662098731239};
    arm.m_nodeDirectionAway = {0.7337380565940257, -0.6794324575008337};
    arm.m_normalAwayPerMeter = {1.1217755833315472e-05, 1.129545721017005e-05};
    arm.m_widthsCm = {350};
    arm.m_turns.resize(arm.m_widthsCm.size());
    junction.m_arms.push_back(arm);
  }
  auto const surface = df::BuildRoadJunctionSurface(junction, mercator::Bounds::FullRect());
  auto const connections = df::BuildRoadLaneConnections(junction);
  TEST_EQUAL(connections.size(), 3, ("Three lanes must connect to two through lanes plus the exit"));
  auto const & wide = junction.m_arms[1];
  double const unit =
      0.00001 / mercator::DistanceOnEarth(junction.m_center, junction.m_center + m2::PointD(0.00001, 0));
  auto const left = wide.m_normalAwayPerMeter.Normalize();
  for (double distance : {1.0, 3.0, 6.0, 9.0})
  {
    auto const center = wide.m_position - wide.m_directionAway * (distance * unit);
    double width = 0;
    for (int step = -250; step <= 250; ++step)
      if (Contains(surface, center + left * (step * 0.05 * unit)))
        width += 0.05;
    TEST_GREATER_OR_EQUAL(width, 10.2, ("Keep the third lane until it branches", distance));
  }
}
UNIT_TEST(RoadJunctionGeometry_RecordedCrossingKeepsThroughSurface)
{
  feature::RoadJunction junction;
  junction.m_center = {37.7669823, 67.3732378};
  {
    feature::RoadJunctionArm arm;
    arm.m_featureId = 142109;
    arm.m_forward = false;
    arm.m_featureEndpoint = true;
    arm.m_oneWay = true;
    arm.m_markings = true;
    arm.m_cutDistance = 12.580554008389704;
    arm.m_position = {37.76693634, 67.37304256};
    arm.m_directionAway = {-0.22913937196310746, -0.9733936244995405};
    arm.m_nodeDirectionAway = {-0.22913937196310744, -0.9733936244995405};
    arm.m_normalAwayPerMeter = {1.5519200277476974e-05, -3.6532598071813606e-06};
    arm.m_widthsCm = {350, 350, 350};
    arm.m_turns.resize(arm.m_widthsCm.size());
    junction.m_arms.push_back(arm);
  }
  {
    feature::RoadJunctionArm arm;
    arm.m_featureId = 4862;
    arm.m_forward = false;
    arm.m_featureEndpoint = true;
    arm.m_oneWay = true;
    arm.m_markings = true;
    arm.m_cutDistance = 21;
    arm.m_position = {37.76665549003624, 67.37331056932678};
    arm.m_directionAway = {-0.9760953320244109, 0.2173428232083022};
    arm.m_nodeDirectionAway = {-0.9760953320244107, 0.2173428232083022};
    arm.m_normalAwayPerMeter = {-3.4651961470975413e-06, -1.5562334811898598e-05};
    arm.m_widthsCm = {350, 350, 350, 350};
    arm.m_turns.resize(arm.m_widthsCm.size());
    junction.m_arms.push_back(arm);
  }
  {
    feature::RoadJunctionArm arm;
    arm.m_featureId = 4863;
    arm.m_forward = true;
    arm.m_featureEndpoint = true;
    arm.m_oneWay = true;
    arm.m_markings = true;
    arm.m_cutDistance = 21;
    arm.m_position = {37.76730788348855, 67.37315973461152};
    arm.m_directionAway = {0.9724378746355906, -0.23316213237619782};
    arm.m_nodeDirectionAway = {0.9724378746355905, -0.23316213237619776};
    arm.m_normalAwayPerMeter = {3.71740255188766e-06, 1.550398857602653e-05};
    arm.m_widthsCm = {350, 350, 350};
    arm.m_turns.resize(arm.m_widthsCm.size());
    junction.m_arms.push_back(arm);
  }
  {
    feature::RoadJunctionArm arm;
    arm.m_featureId = 142110;
    arm.m_forward = true;
    arm.m_featureEndpoint = true;
    arm.m_oneWay = true;
    arm.m_markings = true;
    arm.m_cutDistance = 4.478389606324693;
    arm.m_position = {37.76699806, 67.37330743999999};
    arm.m_directionAway = {0.2207251046967639, 0.9753360590876372};
    arm.m_nodeDirectionAway = {0.22072510469676385, 0.975336059087637};
    arm.m_normalAwayPerMeter = {-1.5550228672763713e-05, 3.5191212504386497e-06};
    arm.m_widthsCm = {350, 350, 350};
    arm.m_turns.resize(arm.m_widthsCm.size());
    junction.m_arms.push_back(arm);
  }
  auto const surface = df::BuildRoadJunctionSurface(junction, mercator::Bounds::FullRect());
  auto const & a = junction.m_arms[1];
  auto const & b = junction.m_arms[2];
  auto const along = (b.m_position - a.m_position).Normalize();
  m2::PointD const left(-along.y, along.x);
  double const unit =
      0.00001 / mercator::DistanceOnEarth(junction.m_center, junction.m_center + m2::PointD(0.00001, 0));
  for (double t : {0.2, 0.4, 0.6, 0.8})
    for (double fraction : {-0.85, -0.5, 0.0, 0.5, 0.85})
    {
      auto const center = a.m_position * (1 - t) + b.m_position * t;
      double const halfWidth = std::lerp(a.WidthMeters(), b.WidthMeters(), t) / 2;
      TEST(Contains(surface, center + left * (halfWidth * fraction * unit)),
           ("No cutout inside the continuing carriageway", t, fraction));
    }
}
}  // namespace road_detail_geometry_tests
