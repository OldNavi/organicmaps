#include "geometry/mercator.hpp"
#include "routing/road_events.hpp"
#include "testing/testing.hpp"

namespace road_events_tests
{
using namespace routing;

UNIT_TEST(RoadEvents_IdentityRolesAndCategories)
{
  RoadEventStore store;
  for (auto kind : {RoadEventKind::SettlementStart, RoadEventKind::SettlementEnd, RoadEventKind::Bump,
                    RoadEventKind::AverageStart, RoadEventKind::AverageEnd})
  {
    RoadEvent event;
    event.m_sourceId = "source:country:identity";
    event.m_kind = kind;
    event.m_position = mercator::FromLatLon(55.1, 38.8);
    store.Add(std::move(event));
  }
  TEST_EQUAL(store.Size(), 5, ());
  TEST(store.Get(0).m_kind == RoadEventKind::SettlementStart, ());
  TEST(store.Get(1).m_kind == RoadEventKind::SettlementEnd, ());
  TEST_EQUAL(store.Get(0).m_sourceId, store.Get(1).m_sourceId, ());
  auto const rect = mercator::RectByCenterXYAndSizeInMeters(mercator::FromLatLon(55.1, 38.8), 1000);
  TEST_EQUAL(store.Query(rect, kAllRoadEventCategories, 100).size(), 5, ());
  TEST_EQUAL(store.Query(rect, 1u << static_cast<unsigned>(RoadEventCategory::Bumps), 100).size(), 1, ());
  TEST(store.Query(rect, 0, 100).empty(), ());
  TEST_EQUAL(store.Query(rect, kAllRoadEventCategories, 2).size(), 2, ());
  auto const away = mercator::RectByCenterXYAndSizeInMeters(mercator::FromLatLon(56, 38), 1000);
  TEST(store.Query(away, kAllRoadEventCategories, 100).empty(), ());
}

UNIT_TEST(RoadEvents_VisibilityFilterKeepsDataAndDoesNotConsumeLimit)
{
  RoadEventStore store;
  for (size_t i = 0; i < 600; ++i)
  {
    RoadEvent event;
    event.m_kind = RoadEventKind::SettlementEnd;
    event.m_position = mercator::FromLatLon(55, 38);
    store.Add(std::move(event));
  }
  RoadEvent bump;
  bump.m_kind = RoadEventKind::Bump;
  bump.m_position = mercator::FromLatLon(55, 38);
  store.Add(std::move(bump));
  auto rect = mercator::RectByCenterXYAndSizeInMeters(mercator::FromLatLon(55, 38), 100);
  auto visible =
      store.Query(rect, kAllRoadEventCategories, 1, 1u << static_cast<unsigned>(RoadEventKind::SettlementEnd));
  TEST_EQUAL(visible.size(), 1, ());
  TEST(store.Get(visible[0]).m_kind == RoadEventKind::Bump, ());
  TEST_EQUAL(store.Query(rect, kAllRoadEventCategories, 1000).size(), 601, ());
}

UNIT_TEST(RoadEvents_CameraApproachDirectionAndDistance)
{
  RoadEvent camera;
  camera.m_position = mercator::FromLatLon(55, 38);
  camera.m_kind = RoadEventKind::Camera;
  camera.m_direction = 0;
  camera.m_directionType = 1;
  camera.m_distance = 500;
  camera.m_angle = 15;
  auto area = BuildCameraApproachArea(camera);
  TEST(!area.m_triangles.empty(), ());
  TEST_EQUAL(area.m_triangles.size() % 3, 0, ());
  for (size_t i = 0; i < area.m_triangles.size(); ++i)
  {
    TEST(area.m_bounds.IsPointInside(area.m_triangles[i]), ());
    TEST(area.m_textureRect.IsPointInside(area.m_triangles[i]), ());
    if (i % 3 == 0)
      TEST_EQUAL(area.m_triangles[i], camera.m_position, ());
    else
    {
      TEST_LESS(area.m_triangles[i].y, camera.m_position.y, ());
      TEST_ALMOST_EQUAL_ABS(mercator::DistanceOnEarth(camera.m_position, area.m_triangles[i]), 500.0, 1.0, ());
    }
  }
  camera.m_directionType = 2;
  auto bidirectional = BuildCameraApproachArea(camera);
  TEST_EQUAL(bidirectional.m_triangles.size(), area.m_triangles.size() * 2, ());
  TEST_GREATER(bidirectional.m_bounds.maxY(), camera.m_position.y, ());
  camera.m_directionType = 0;
  TEST(BuildCameraApproachArea(camera).m_triangles.empty(), ());
  camera.m_directionType = 1;
  camera.m_angle = 0;
  TEST(BuildCameraApproachArea(camera).m_triangles.empty(), ());
  camera.m_angle = 15;
  camera.m_kind = RoadEventKind::Bump;
  TEST(BuildCameraApproachArea(camera).m_triangles.empty(), ());
}

UNIT_TEST(RoadEvents_NormalizedDirection)
{
  RoadEvent event;
  event.m_directionType = 1;
  event.m_direction = 0;
  event.m_angle = 20;
  TEST(event.MatchesBearing(0), ());
  TEST(event.MatchesBearing(359), ());
  TEST(event.MatchesBearing(20), ());
  TEST(!event.MatchesBearing(21), ());
  TEST(!event.MatchesBearing(180), ());
  event.m_directionType = 2;
  TEST(event.MatchesBearing(180), ());
  TEST(!event.MatchesBearing(90), ());
  event.m_directionType = 0;
  TEST(event.MatchesBearing(90), ());
}

UNIT_TEST(RoadEvents_DisableRetainsDatabase)
{
  RoadEventSource source;
  auto store = std::make_shared<RoadEventStore>();
  source.Replace(store);
  source.Configure(true, true, 5);
  auto before = source.Get();
  source.Configure(false, false, 5);
  auto after = source.Get();
  TEST(!after.m_enabled, ());
  TEST_EQUAL(after.m_store, before.m_store, ());
  TEST_EQUAL(after.m_visibleKinds, 5, ());
  TEST_GREATER(after.m_revision, before.m_revision, ());
}
}  // namespace road_events_tests

UNIT_TEST(RoadEvents_SpeedCameraTypesExcludeOtherEnforcement)
{
  using routing::RoadEventKind;
  for (auto kind :
       {RoadEventKind::Camera, RoadEventKind::Mobile, RoadEventKind::AverageStart, RoadEventKind::AverageEnd})
    TEST(routing::IsSpeedCamera(kind), ());
  for (auto kind : {RoadEventKind::Dummy, RoadEventKind::Police, RoadEventKind::Video, RoadEventKind::RedLight,
                    RoadEventKind::LaneControl, RoadEventKind::SpeedLimit, RoadEventKind::SettlementStart,
                    RoadEventKind::SettlementEnd, RoadEventKind::Bump, RoadEventKind::Crossing, RoadEventKind::Children,
                    RoadEventKind::Railway, RoadEventKind::BadRoad, RoadEventKind::Bend, RoadEventKind::Intersection,
                    RoadEventKind::Danger, RoadEventKind::NoOvertaking})
    TEST(!routing::IsSpeedCamera(kind), ());
  TEST(routing::IsCamera(RoadEventKind::Dummy), ("The display category still includes dummies"));
  TEST(routing::IsCamera(RoadEventKind::Police), ());
}

UNIT_TEST(RoadEvents_ApproachSectorUsesLocationDirectionAndDistance)
{
  routing::RoadEvent event;
  event.m_position = mercator::FromLatLon(55, 38);
  event.m_direction = 0;
  event.m_directionType = 1;
  event.m_angle = 20;
  event.m_distance = 200;
  TEST(event.IsInApproachSector(mercator::FromLatLon(54.999, 38)), ());
  TEST(!event.IsInApproachSector(mercator::FromLatLon(55.001, 38)), ("Opposite approach"));
  TEST(!event.IsInApproachSector(mercator::FromLatLon(55, 37.999)), ("Outside angular sector"));
  TEST(!event.IsInApproachSector(mercator::FromLatLon(54.997, 38)), ("Outside warning radius"));
  event.m_directionType = 2;
  TEST(event.IsInApproachSector(mercator::FromLatLon(55.001, 38)), ());
  event.m_directionType = 0;
  TEST(event.IsInApproachSector(mercator::FromLatLon(55, 37.999)), ());
  event.m_distance = 0;
  TEST(!event.IsInApproachSector(mercator::FromLatLon(54.999, 38)), ());
}
