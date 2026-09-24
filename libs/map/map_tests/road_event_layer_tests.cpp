#include "testing/testing.hpp"

#include "base/file_name_utils.hpp"
#include "coding/files_container.hpp"
#include "defines.hpp"
#include "generator/generator_tests_support/test_feature.hpp"
#include "generator/generator_tests_support/test_mwm_builder.hpp"
#include "indexer/classificator_loader.hpp"
#include "map/place_page_info.hpp"
#include "map/road_event_layer.hpp"
#include "platform/platform.hpp"
#include "platform/platform_tests_support/scoped_dir.hpp"
#include "platform/platform_tests_support/scoped_file.hpp"
#include "routing/speed_camera_ser_des.hpp"

UNIT_TEST(RoadEventLayer_OverviewAndCameraAreas)
{
  classificator::Load();
  df::VisualParams::Init(1, 1024);
  auto source = std::make_shared<routing::RoadEventSource>();
  auto store = std::make_shared<routing::RoadEventStore>();
  using Kind = routing::RoadEventKind;
  for (auto kind : {Kind::Camera, Kind::Bump, Kind::SettlementStart, Kind::SettlementEnd})
  {
    routing::RoadEvent event;
    event.m_kind = kind;
    event.m_position = mercator::FromLatLon(55, 38);
    event.m_directionType = 1;
    event.m_direction = 0;
    event.m_angle = 15;
    event.m_distance = 500;
    store->Add(std::move(event));
  }
  source->Replace(store);
  source->Configure(true, true, routing::kAllRoadEventKinds);
  RoadEventLayer layer(source);
  auto const rect = mercator::RectByCenterXYAndSizeInMeters(mercator::FromLatLon(55, 38), 1000);
  TEST(layer.Query(rect, 12).m_marks->empty(), ());
  auto overview = layer.Query(rect, 13);
  TEST_EQUAL(overview.m_marks->size(), 3, ());
  TEST(overview.m_lines->empty(), ());
  for (auto const & [id, mark] : *overview.m_marks)
  {
    TEST(!mark->m_symbolIsPOI, ());
    TEST(mark->m_isSymbolSelectable, ());
    TEST(mark->m_depthLayer == df::DepthLayer::UserMarkLayer, ());
    TEST(mark->m_symbolNames->at(1).ends_with("-s"), ());
    TEST(mark->m_symbolNames->at(15).ends_with("-m"), ());
    TEST(mark->m_symbolNames->at(17).ends_with("-l"), ());
  }
  auto detail = layer.Query(rect, 16);
  TEST_EQUAL(detail.m_lines->size(), 1, ());
  TEST(detail.m_lines->begin()->second->m_fill != nullptr, ());
  source->Configure(true, true, 1u << static_cast<unsigned>(routing::RoadEventKind::Bump));
  auto bumpsOnly = layer.Query(rect, 16);
  TEST_EQUAL(bumpsOnly.m_marks->size(), 1, ());
  TEST(bumpsOnly.m_lines->empty(), ());
  source->Configure(false, true, routing::kAllRoadEventKinds);
  auto disabled = layer.Query(rect, 16);
  TEST(disabled.m_marks->empty(), ());
  TEST(disabled.m_lines->empty(), ());
  TEST_EQUAL(source->Get().m_store->Size(), 4, ());
}

UNIT_TEST(RoadEventLayer_DenseViewportIsNotTruncated)
{
  classificator::Load();
  df::VisualParams::Init(1, 1024);
  auto source = std::make_shared<routing::RoadEventSource>();
  auto store = std::make_shared<routing::RoadEventStore>();
  for (size_t i = 0; i < 700; ++i)
  {
    routing::RoadEvent event;
    event.m_kind = i == 699 ? routing::RoadEventKind::Camera : routing::RoadEventKind::Bump;
    event.m_position = mercator::FromLatLon(55, 38);
    event.m_directionType = 1;
    event.m_angle = 15;
    event.m_distance = 500;
    store->Add(std::move(event));
  }
  source->Replace(store);
  source->Configure(true, true, routing::kAllRoadEventKinds);
  RoadEventLayer layer(source);
  auto data = layer.Query(mercator::RectByCenterXYAndSizeInMeters(mercator::FromLatLon(55, 38), 1000), 16);
  TEST_EQUAL(data.m_marks->size(), 700, ());
  TEST_EQUAL(data.m_lines->size(), 1, ());
  TEST(data.m_marks->contains(data.m_lines->begin()->first), ());
}

UNIT_TEST(RoadEventLayer_SelectionUsesRenderedSnapshot)
{
  classificator::Load();
  df::VisualParams::Init(1, 1024);
  auto source = std::make_shared<routing::RoadEventSource>();
  auto store = std::make_shared<routing::RoadEventStore>();
  for (auto kind : {routing::RoadEventKind::Camera, routing::RoadEventKind::SpeedLimit})
  {
    routing::RoadEvent event;
    // A source may use the same ID and position for several roles.
    event.m_sourceId = "example.org:RU:same-id";
    event.m_kind = kind;
    event.m_position = mercator::FromLatLon(55, 38);
    event.m_speedKmh = 90;
    event.m_importedAt = 1790098067000;
    store->Add(std::move(event));
  }
  source->Replace(store);
  source->Configure(true, true, routing::kAllRoadEventKinds);
  RoadEventLayer layer(source);
  auto const rect = mercator::RectByCenterXYAndSizeInMeters(mercator::FromLatLon(55, 38), 1000);
  auto data = layer.Query(rect, 17);
  TEST_EQUAL(data.m_marks->size(), 2, ());
  for (auto const & [id, mark] : *data.m_marks)
  {
    auto selected = RoadEventLayer::Resolve(*source, id);
    TEST(selected, ());
    TEST_EQUAL(selected->m_sourceId, "example.org:RU:same-id", ());
    TEST_EQUAL(selected->m_importedAt, 1790098067000, ());
    TEST_EQUAL(selected->m_speedKmh, 90, ());
    TEST(selected->m_kind == store->Get(mark->m_index).m_kind, ());
  }
  auto const id = data.m_marks->begin()->first;
  TEST(!RoadEventLayer::Resolve(*source, kml::kInvalidMarkId), ());
  TEST(!RoadEventLayer::Resolve(*source, 1), ());
  TEST(!RoadEventLayer::Resolve(*source, (id & ~uint64_t{0xffffffff}) | 100), ());
  place_page::BuildInfo buildInfo;
  buildInfo.m_roadEvent = RoadEventLayer::Resolve(*source, id);
  place_page::Info info;
  info.SetBuildInfo(buildInfo);
  info.FillRoadEventInfo();
  TEST(info.GetSelectedObject() == df::SelectionShape::OBJECT_POI,
       ("Activating the place page must never receive OBJECT_EMPTY"));
  TEST_EQUAL(info.GetMercator(), mercator::FromLatLon(55, 38), ());
  TEST(!info.CanEditPlace(), ());
  TEST(!info.IsFeature(), ());
  source->Replace(store);
  TEST_EQUAL(info.GetBuildInfo().m_roadEvent->m_importedAt, 1790098067000, ("Selected details retain their snapshot"));
  TEST(!RoadEventLayer::Resolve(*source, id), ("Never resolve an old geometry ID against a new index"));
  data = layer.Query(rect, 17);
  auto const refreshedId = data.m_marks->begin()->first;
  TEST(RoadEventLayer::Resolve(*source, refreshedId), ());
  source->Configure(true, true, 0);
  TEST(!RoadEventLayer::Resolve(*source, refreshedId), ("Hidden categories cannot still be selected"));
  source->Configure(false, true, routing::kAllRoadEventKinds);
  TEST(!RoadEventLayer::Resolve(*source, refreshedId), ());
}

UNIT_TEST(RoadEventLayer_PerKindZoomThresholds)
{
  classificator::Load();
  df::VisualParams::Init(1, 1024);
  auto source = std::make_shared<routing::RoadEventSource>();
  auto store = std::make_shared<routing::RoadEventStore>();
  using Kind = routing::RoadEventKind;
  for (auto kind : {Kind::Camera, Kind::Dummy, Kind::Bump})
  {
    routing::RoadEvent event;
    event.m_kind = kind;
    event.m_position = mercator::FromLatLon(55, kind == Kind::Dummy ? 38.003 : 38);
    event.m_directionType = 1;
    event.m_angle = 15;
    event.m_distance = 500;
    store->Add(std::move(event));
  }
  auto zooms = routing::kDefaultRoadEventMinZooms;
  zooms[static_cast<size_t>(Kind::Camera)] = 12;
  zooms[static_cast<size_t>(Kind::Dummy)] = 16;
  zooms[static_cast<size_t>(Kind::Bump)] = 16;
  source->Replace(store);
  source->Configure(true, true, routing::kAllRoadEventKinds, zooms);
  RoadEventLayer layer(source);
  auto const rect = mercator::RectByCenterXYAndSizeInMeters(mercator::FromLatLon(55, 38), 1000);
  TEST(layer.Query(rect, 11).m_marks->empty(), ());
  for (int zoom : {12, 13, 15})
  {
    auto data = layer.Query(rect, zoom);
    TEST_EQUAL(data.m_marks->size(), 1, ());
    auto const & mark = *data.m_marks->begin()->second;
    TEST_EQUAL(mark.m_minZoom, 12, ());
    TEST_EQUAL(mark.m_symbolNames->at(1), "road-event-camera-s", ());
  }
  auto detail = layer.Query(rect, 16);
  TEST_EQUAL(detail.m_marks->size(), 3, ());
  bool foundDummy = false;
  for (auto const & [id, mark] : *detail.m_marks)
    if (RoadEventLayer::Resolve(*source, id)->m_kind == Kind::Dummy)
      foundDummy = mark->m_symbolNames->at(15) == "road-event-dummy-m";
  TEST(foundDummy, ());
  TEST_EQUAL(detail.m_lines->size(), 1, ("A dummy never draws a camera sector"));
  auto revision = source->Get().m_revision;
  source->Configure(true, true, routing::kAllRoadEventKinds, zooms);
  TEST_EQUAL(source->Get().m_revision, revision, ());
  zooms[static_cast<size_t>(Kind::Camera)] = 18;
  source->Configure(true, true, routing::kAllRoadEventKinds, zooms);
  TEST_GREATER(source->Get().m_revision, revision, ());
  TEST(layer.Query(rect, 15).m_lines->empty(), ("A hidden camera must not leave an orphaned sector"));
  TEST_EQUAL(layer.Query(rect, 18).m_lines->size(), 1, ());
}

UNIT_TEST(RoadEventLayer_CameraDeclutteringRespectsAzimuthZoomAndSpeedPriority)
{
  classificator::Load();
  df::VisualParams::Init(1, 1024);
  auto source = std::make_shared<routing::RoadEventSource>();
  auto store = std::make_shared<routing::RoadEventStore>();
  using Kind = routing::RoadEventKind;
  auto const point = mercator::FromLatLon(55, 38);
  double const radius = 24 * df::GetScreenScale(13);
  auto add = [&](Kind kind, unsigned bearing, double offset, char const * id, unsigned directionType = 1)
  {
    routing::RoadEvent event;
    event.m_kind = kind;
    event.m_sourceId = id;
    event.m_position = point + m2::PointD(offset, 0);
    event.m_directionType = directionType;
    event.m_direction = bearing;
    store->Add(std::move(event));
  };
  add(Kind::LaneControl, 0, 0, "a-lane");
  add(Kind::RedLight, 0, 0, "b-cross");
  add(Kind::Dummy, 0, 0, "c-dummy");
  add(Kind::Camera, 359, 0, "speed-first");
  add(Kind::Camera, 1, radius * 0.5, "speed-near");
  add(Kind::Camera, 180, 0, "opposite");
  add(Kind::Camera, 90, 0, "perpendicular");
  add(Kind::Camera, 0, 0, "unknown-direction", 0);
  add(Kind::Bump, 0, 0, "bump");
  source->Replace(store);
  source->Configure(true, true, routing::kAllRoadEventKinds);
  RoadEventLayer layer(source);
  m2::RectD const rect(point.x - 2 * radius, point.y - 2 * radius, point.x + 2 * radius, point.y + 2 * radius);
  auto overview = layer.Query(rect, 13);
  TEST_EQUAL(overview.m_marks->size(), 5, ());
  for (auto const & [id, mark] : *overview.m_marks)
  {
    auto event = RoadEventLayer::Resolve(*source, id);
    TEST(event->m_kind == Kind::Camera || event->m_kind == Kind::Bump, ("Speed cameras win over other camera kinds"));
    TEST_NOT_EQUAL(event->m_sourceId, "speed-near", ());
  }
  TEST_EQUAL(layer.Query(rect, 19).m_marks->size(), 6, ("Nearby distinct points reappear when zoomed in"));
  TEST_EQUAL(store->Size(), 9, ("Display decluttering must not remove warning/provider data"));
}

UNIT_TEST(RoadEventLayer_VideoSurveillanceHasItsOwnSymbolAndNoSector)
{
  classificator::Load();
  df::VisualParams::Init(1, 1024);
  auto source = std::make_shared<routing::RoadEventSource>();
  auto store = std::make_shared<routing::RoadEventStore>();
  routing::RoadEvent event;
  event.m_kind = routing::RoadEventKind::Video;
  event.m_position = mercator::FromLatLon(55, 38);
  event.m_directionType = 1;
  event.m_angle = 20;
  event.m_distance = 500;
  auto const rect = mercator::RectByCenterXYAndSizeInMeters(event.m_position, 1000);
  TEST(routing::BuildCameraApproachArea(event).m_triangles.empty(), ());
  TEST(!routing::IsSpeedCamera(event.m_kind), ());
  store->Add(std::move(event));
  auto zooms = routing::kDefaultRoadEventMinZooms;
  zooms[static_cast<size_t>(routing::RoadEventKind::Video)] = 16;
  source->Replace(store);
  source->Configure(true, true, routing::kAllRoadEventKinds, zooms);
  RoadEventLayer layer(source);
  TEST(layer.Query(rect, 15).m_marks->empty(), ());
  auto data = layer.Query(rect, 16);
  TEST_EQUAL(data.m_marks->size(), 1, ());
  TEST_EQUAL(data.m_marks->begin()->second->m_symbolNames->at(15), "road-event-video-m", ());
  TEST(data.m_lines->empty(), ());
}

UNIT_TEST(RoadEventLayer_MwmCameraMergeAndBadges)
{
  classificator::Load();
  df::VisualParams::Init(1, 1024);
  auto source = std::make_shared<routing::RoadEventSource>();
  auto imported = std::make_shared<routing::RoadEventStore>();
  auto native = std::make_shared<routing::RoadEventStore>();
  auto const point = mercator::FromLatLon(55, 38);
  routing::RoadEvent camera;
  camera.m_sourceId = "example.org:RU:camera";
  camera.m_position = point;
  camera.m_speedKmh = 90;
  camera.m_directionType = 1;
  camera.m_direction = 359;
  camera.m_distance = 500;
  camera.m_angle = 15;
  imported->Add(routing::RoadEvent(camera));
  camera.m_sourceId = "OpenStreetMap:Region:1";
  camera.m_position = mercator::GetSmPoint(point, 20, 0);
  camera.m_direction = 1;
  native->Add(routing::RoadEvent(camera));
  camera.m_direction = 180;
  camera.m_sourceId = "OpenStreetMap:Region:2";
  native->Add(routing::RoadEvent(camera));
  camera.m_position = mercator::GetSmPoint(point, 100, 0);
  camera.m_direction = 0;
  camera.m_sourceId = "OpenStreetMap:Region:3";
  native->Add(routing::RoadEvent(camera));
  source->Replace(imported);
  source->ReplaceMapCameras(native);
  source->Configure(true, true, routing::kAllRoadEventKinds);
  RoadEventLayer layer(source);
  auto const rect = mercator::RectByCenterXYAndSizeInMeters(point, 1000);
  TEST_EQUAL(layer.Query(rect, 19).m_marks->size(), 1, ("Stock flavor does not opt into MWM replacement"));
  source->EnableMapCameras();
  auto data = layer.Query(rect, 19);
  TEST_EQUAL(data.m_marks->size(), 3, ("Only nearby cameras with matching direction are duplicates"));
  TEST_EQUAL(data.m_lines->size(), 3, ("One sector per retained camera"));
  for (auto const & [id, mark] : *data.m_marks)
  {
    auto const selected = RoadEventLayer::Resolve(*source, id);
    TEST(selected, ());
    TEST_NOT_EQUAL(selected->m_sourceId, "OpenStreetMap:Region:1", ("Imported camera takes precedence"));
    TEST_EQUAL(mark->m_symbolNames->at(17), "road-event-camera-l", ());
    TEST_EQUAL(mark->m_titleDecl->front().m_primaryText, "90", ());
    TEST(mark->m_coloredSymbols && mark->m_coloredSymbols->m_addTextSize, ("Same speed badge as native cameras"));
  }
  source->Configure(true, true, 0);
  TEST(layer.Query(rect, 19).m_marks->empty(), ("Profile visibility applies to both sources"));
  for (auto const & [id, mark] : *data.m_marks)
    TEST(!RoadEventLayer::Resolve(*source, id), ("Stale selection cannot read another snapshot"));
}

UNIT_TEST(RoadEventLayer_ReadMwmCameraDirectionsAndFallbackSectors)
{
  classificator::Load();
  using namespace generator::tests_support;
  using namespace routing;
  platform::tests_support::ScopedDirCleanup directory(
      base::JoinPath(GetPlatform().WritableDir(), "road_event_mwm_test"));
  platform::LocalCountryFile country(base::JoinPath(GetPlatform().WritableDir(), "road_event_mwm_test"),
                                     platform::CountryFile("RoadEventTestLand"), 0);
  {
    TestMwmBuilder builder(country, feature::DataHeader::MapType::Country);
    builder.Add(TestStreet({{38, 55}, {38.01, 55}}, "Camera road", "en"));
  }
  platform::tests_support::ScopedFile section("road_event_cameras.bin", "");
  {
    FileWriter writer(section.GetFullPath());
    SpeedCameraMwmHeader header;
    header.SetAmount(4);
    header.Serialize(writer);
    uint32_t previous = 0;
    for (auto direction : {SpeedCameraDirection::Unknown, SpeedCameraDirection::Forward, SpeedCameraDirection::Backward,
                           SpeedCameraDirection::Both})
    {
      SpeedCameraMetadata metadata({}, 60, {{0, 0, 0.5}});
      metadata.m_direction = direction;
      SerializeSpeedCamera(writer, metadata, previous);
    }
  }
  {
    FilesContainerW container(country.GetPath(MapFileType::Map), FileWriter::OP_WRITE_EXISTING);
    container.Write(section.GetFullPath(), CAMERAS_INFO_FILE_TAG);
  }
  FrozenDataSource data;
  auto const [id, registered] = data.RegisterMap(country);
  TEST(registered == MwmSet::RegResult::Success, ());
  auto store = MwmRoadEvents::Read(data, id);
  TEST_EQUAL(store->Size(), 4, ());
  for (size_t i = 0; i < store->Size(); ++i)
  {
    auto const & event = store->Get(i);
    TEST_EQUAL(event.m_speedKmh, 60, ());
    TEST_ALMOST_EQUAL_ABS(event.m_position.x, 38.005, 1e-5, ());
    TEST_EQUAL(event.m_direction, i == 2 ? 270 : 90, ());
    TEST_EQUAL(event.m_directionType, i == 1 || i == 2 ? 1 : 2, ());
    TEST(!BuildCameraApproachArea(event).m_triangles.empty(), ());
    TEST_EQUAL(event.m_distance, 500, ());
    TEST_EQUAL(event.m_angle, 15, ());
  }
  data.Deregister(country.GetCountryFile());
  data.ClearCache();
}
