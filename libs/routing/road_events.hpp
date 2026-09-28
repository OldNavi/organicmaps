#pragma once

#include "geometry/point2d.hpp"
#include "geometry/tree4d.hpp"

#include <array>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

namespace routing
{
class CameraCoveragePath;
enum class RoadEventKind : uint8_t
{
  Camera,
  Dummy,
  Video,
  RedLight,
  LaneControl,
  Mobile,
  Police,
  AverageStart,
  AverageEnd,
  SpeedLimit,
  SettlementStart,
  SettlementEnd,
  Bump,
  Crossing,
  Children,
  Railway,
  BadRoad,
  Bend,
  Intersection,
  Danger,
  NoOvertaking,
  Count
};

constexpr uint32_t kAllRoadEventKinds = (1u << static_cast<unsigned>(RoadEventKind::Count)) - 1;

enum class RoadEventCategory : uint8_t
{
  Cameras,
  AverageSpeed,
  SpeedLimits,
  Settlements,
  Bumps,
  Crossings,
  Railways,
  Hazards,
  NoOvertaking,
  Count
};
using RoadEventMinZooms = std::array<int, static_cast<size_t>(RoadEventKind::Count)>;
inline constexpr RoadEventMinZooms kDefaultRoadEventMinZooms = []
{
  RoadEventMinZooms result;
  result.fill(13);
  return result;
}();
constexpr uint32_t kAllRoadEventCategories = (1u << static_cast<unsigned>(RoadEventCategory::Count)) - 1;
RoadEventCategory GetCategory(RoadEventKind kind);
bool IsCamera(RoadEventKind kind);
bool IsSpeedCamera(RoadEventKind kind);
inline constexpr double kRoadEventRoadDistanceMeters = 12;
inline constexpr double kMaxCameraRoadDistanceMeters = 100;

struct RoadEvent
{
  std::string m_sourceId;
  uint64_t m_importedAt = 0;  // Unix milliseconds, from the same database snapshot as the event.
  m2::PointD m_position;
  RoadEventKind m_kind = RoadEventKind::Camera;
  uint16_t m_speedKmh = 0;
  uint16_t m_distance = 0;
  uint16_t m_direction = 0;  // Bearing from the approach sector towards the event, clockwise from north.
  uint8_t m_directionType = 0;
  uint8_t m_angle = 0;

  bool MatchesBearing(double travelBearing) const;
  bool MatchesRoadBearing(double travelBearing) const;
  bool IsInApproachSector(m2::PointD const & position) const;
  bool operator==(RoadEvent const &) const = default;
};

struct CameraApproachArea
{
  m2::RectD m_bounds;
  m2::RectD m_textureRect;
  std::vector<m2::PointD> m_triangles;
};

// Visualize the provider's warning distance, not a measured camera detection range.
CameraApproachArea BuildCameraApproachArea(RoadEvent const & event);
double RoadEventMatchRadius(RoadEvent const & event);

// Immutable after construction. Renderers and the road worker share one spatial index.
class RoadEventStore
{
public:
  std::vector<size_t> Query(m2::RectD const & rect, uint32_t categories, size_t limit,
                            uint32_t excludedKinds = 0) const;
  RoadEvent const & Get(size_t index) const { return m_events.at(index); }
  size_t Size() const { return m_events.size(); }

  void Add(RoadEvent && event);

private:
  std::vector<RoadEvent> m_events;
  m4::Tree<size_t> m_index;
};

class RoadEventSource
{
public:
  struct Coverage
  {
    std::vector<size_t> m_imported;
    std::vector<size_t> m_map;
    bool operator==(Coverage const &) const = default;
  };
  struct Snapshot
  {
    std::shared_ptr<RoadEventStore const> m_store;
    std::shared_ptr<RoadEventStore const> m_mapCameras;
    bool m_useMapCameras = false;
    uint64_t m_revision = 0;
    uint32_t m_visibleKinds = kAllRoadEventKinds;
    bool m_enabled = false;
    bool m_warnings = true;
    bool m_coverageVisible = true;
    bool m_filterCoverage = false;
    uint64_t m_coverageGeneration = 0;
    std::shared_ptr<CameraCoveragePath const> m_coverageRoute;
    std::shared_ptr<Coverage const> m_coverage;
    std::shared_ptr<RoadEvent const> m_coveragePreview;
    RoadEventMinZooms m_minZooms = kDefaultRoadEventMinZooms;
  };
  Snapshot Get() const;
  void Replace(std::shared_ptr<RoadEventStore const> store);
  void ReplaceMapCameras(std::shared_ptr<RoadEventStore const> store);
  void EnableMapCameras();
  void InvalidateMapCameras();
  bool SetCoverageVisible(bool visible);
  void EnableCoverageFilter();
  void SetCoverageRoute(std::shared_ptr<CameraCoveragePath const> route);
  bool ClearCoverage();
  bool SetCoverage(Snapshot const & input, Coverage coverage);
  bool SetCoveragePreview(std::optional<RoadEvent> const & event);
  void Configure(bool enabled, bool warnings, uint32_t visibleKinds,
                 RoadEventMinZooms const & minZooms = kDefaultRoadEventMinZooms);

private:
  mutable std::mutex m_mutex;
  Snapshot m_state;
};
}  // namespace routing
