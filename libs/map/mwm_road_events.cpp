#include "map/mwm_road_events.hpp"

#include "base/math.hpp"
#include "defines.hpp"
#include "geometry/mercator.hpp"
#include "indexer/classificator.hpp"
#include "indexer/feature.hpp"
#include "indexer/feature_utils.hpp"
#include "indexer/scales.hpp"
#include "platform/platform.hpp"
#include "routing/speed_camera_prohibition.hpp"
#include "routing/speed_camera_ser_des.hpp"

#include <cmath>

MwmRoadEvents::MwmRoadEvents(DataSource const & dataSource, std::shared_ptr<routing::RoadEventSource> source,
                             std::function<void()> changed)
  : m_dataSource(dataSource)
  , m_source(std::move(source))
  , m_changed(std::move(changed))
{}

std::shared_ptr<routing::RoadEventStore> MwmRoadEvents::Read(DataSource const & source, MwmSet::MwmId const & id)
{
  auto result = std::make_shared<routing::RoadEventStore>();
  auto handle = source.GetMwmHandleById(id);
  if (!handle.IsAlive() || routing::AreSpeedCamerasProhibited(id.GetInfo()->GetLocalFile().GetCountryFile()))
    return result;
  auto const & container = handle.GetValue()->m_cont;
  if (!container.IsExist(CAMERAS_INFO_FILE_TAG))
    return result;
  auto reader = container.GetReader(CAMERAS_INFO_FILE_TAG);
  ReaderSource<FilesContainerR::TReader> input(reader);
  routing::SpeedCameraMwmHeader header;
  header.Deserialize(input);
  CHECK(header.IsValid(), ());
  auto features = source.CreateFeatureSource(handle);
  std::unique_ptr<FeatureType> road;
  uint32_t previous = 0, loaded = std::numeric_limits<uint32_t>::max();
  for (uint32_t i = 0; i < header.GetAmount(); ++i)
  {
    routing::SpeedCameraDirection direction;
    auto const [position, camera] = routing::DeserializeSpeedCamera(input, previous, &direction);
    if (loaded != position.GetFeatureId())
    {
      road = features->GetOriginalFeature(position.GetFeatureId());
      loaded = position.GetFeatureId();
      if (road)
        road->ParseGeometry(FeatureType::BEST_GEOMETRY);
    }
    if (!road || position.GetPointId() + 1 >= road->GetPointsCount())
      continue;
    auto const start = road->GetPoint(position.GetPointId());
    auto const delta = road->GetPoint(position.GetPointId() + 1) - start;
    if (delta.IsAlmostZero())
      continue;
    routing::RoadEvent event;
    event.m_sourceId = "OpenStreetMap:" + id.GetInfo()->GetCountryName() + ":" + std::to_string(i);
    event.m_position = start + delta * camera.m_coef;
    event.m_speedKmh = camera.m_maxSpeedKmPH == routing::SpeedCameraOnRoute::kNoSpeedInfo ? 0 : camera.m_maxSpeedKmPH;
    double bearing = math::RadToDeg(std::atan2(delta.x, delta.y));
    if (direction == routing::SpeedCameraDirection::Backward)
      bearing += 180.0;
    event.m_direction = static_cast<uint16_t>(std::lround(bearing + 360.0)) % 360;
    event.m_directionType =
        direction == routing::SpeedCameraDirection::Forward || direction == routing::SpeedCameraDirection::Backward ? 1
                                                                                                                    : 2;
    // MWM has no detection range/FOV. Draw a conventional warning sector along the road;
    // unknown direction uses both approaches, never an invented one-way camera bearing.
    event.m_distance = kDefaultDistanceMeters;
    event.m_angle = kDefaultHalfAngleDegrees;
    result->Add(std::move(event));
  }
  return result;
}

void MwmRoadEvents::Request(m2::RectD const & rect)
{
  std::vector<std::shared_ptr<MwmInfo>> maps;
  m_dataSource.GetMwmsInfo(maps);
  std::vector<MwmSet::MwmId> pending;
  bool removed = false;
  {
    std::lock_guard lock(m_mutex);
    removed = std::erase_if(m_maps, [](auto const & entry) { return !entry.first.IsAlive(); }) != 0;
    for (auto const & info : maps)
    {
      MwmSet::MwmId id(info);
      if (id.IsAlive() && info->GetType() == MwmInfo::COUNTRY && info->m_bordersRect.IsIntersect(rect) &&
          m_maps.try_emplace(id, nullptr).second)
        pending.push_back(id);
    }
  }
  if (pending.empty() && !removed)
    return;
  GetPlatform().RunTask(Platform::Thread::File, [self = shared_from_this(), pending = std::move(pending)]
  {
    for (auto const & id : pending)
    {
      auto store = Read(self->m_dataSource, id);
      std::lock_guard lock(self->m_mutex);
      if (auto it = self->m_maps.find(id); it != self->m_maps.end())
        it->second = std::move(store);
    }
    std::vector<std::shared_ptr<routing::RoadEventStore const>> ready;
    {
      std::lock_guard lock(self->m_mutex);
      for (auto const & [id, store] : self->m_maps)
        if (id.IsAlive() && store)
          ready.push_back(store);
    }
    // Building the spatial tree must not hold the mutex used by viewport requests.
    auto combined = std::make_shared<routing::RoadEventStore>();
    for (auto const & store : ready)
      for (size_t i = 0; i < store->Size(); ++i)
        combined->Add(routing::RoadEvent(store->Get(i)));
    self->m_source->ReplaceMapCameras(std::move(combined));
    self->m_changed();
  });
}

bool MwmRoadEvents::Replaces(FeatureType & feature, routing::RoadEventSource::Snapshot const & snapshot)
{
  if (!snapshot.m_useMapCameras || !snapshot.m_mapCameras || feature.GetGeomType() != feature::GeomType::Point)
    return false;
  static uint32_t const cameraType = classif().GetTypeByPath({"highway", "speed_camera"});
  if (!feature::TypesHolder(feature).Has(cameraType))
    return false;
  auto const point = feature::GetCenter(feature);
  auto const rect = mercator::RectByCenterXYAndSizeInMeters(point, kDuplicateDistanceMeters);
  // Some camera nodes have no routable segment. Keep those POIs rather than dropping information.
  for (auto index :
       snapshot.m_mapCameras->Query(rect, routing::kAllRoadEventCategories, std::numeric_limits<size_t>::max()))
    if (mercator::DistanceOnEarth(point, snapshot.m_mapCameras->Get(index).m_position) <= kDuplicateDistanceMeters)
      return true;
  return false;
}

void MwmRoadEvents::Invalidate()
{
  if (!m_source->Get().m_useMapCameras)
    return;
  // A map can change while the viewport stays still. Requery registered MWM IDs on the next refresh.
  m_source->InvalidateMapCameras();
  m_changed();
}
