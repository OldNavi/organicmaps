#pragma once

#include "indexer/data_source.hpp"
#include "routing/road_events.hpp"

#include <functional>
#include <map>
#include <mutex>

// Shared by all map views. MWM sections and road geometry are read once on the file thread.
class MwmRoadEvents : public std::enable_shared_from_this<MwmRoadEvents>
{
public:
  static constexpr double kDuplicateDistanceMeters = 50.0;
  static constexpr uint16_t kDefaultDistanceMeters = 500;
  static constexpr uint8_t kDefaultHalfAngleDegrees = 15;
  MwmRoadEvents(DataSource const & dataSource, std::shared_ptr<routing::RoadEventSource> source,
                std::function<void()> changed);
  void Request(m2::RectD const & rect);
  void Invalidate();
  static bool Replaces(FeatureType & feature, routing::RoadEventSource::Snapshot const & snapshot);
  static std::shared_ptr<routing::RoadEventStore> Read(DataSource const & source, MwmSet::MwmId const & id);

private:
  DataSource const & m_dataSource;
  std::shared_ptr<routing::RoadEventSource> m_source;
  std::function<void()> m_changed;
  std::mutex m_mutex;
  std::map<MwmSet::MwmId, std::shared_ptr<routing::RoadEventStore const>> m_maps;
};
