#pragma once

#include "geometry/point2d.hpp"
#include "geometry/rect2d.hpp"

#include "coding/files_container.hpp"

#include <cstdint>
#include <memory>
#include <vector>

namespace feature
{
struct RoadJunctionArm
{
  uint32_t m_featureId = 0;
  bool m_forward = false;
  bool m_featureEndpoint = false;
  bool m_oneWay = false;
  bool m_markings = false;
  double m_nodeDistance = 0;
  double m_cutDistance = 0;
  m2::PointD m_position;
  m2::PointD m_directionAway;
  m2::PointD m_nodeDirectionAway;
  m2::PointD m_normalAwayPerMeter;
  std::vector<uint16_t> m_widthsCm;
  std::vector<uint16_t> m_turns;

  double WidthMeters() const;
};

struct RoadJunction
{
  uint32_t m_ownerFeatureId = 0;
  uint8_t m_continuationA = 255, m_continuationB = 255;
  bool HasContinuation() const { return m_continuationA != 255; }
  m2::PointD m_center;
  std::vector<RoadJunctionArm> m_arms;
  m2::RectD Bounds() const;
};

struct RoadJunctionLink
{
  RoadJunction const * m_junction = nullptr;
  size_t m_armIndex = 0;
  explicit operator bool() const { return m_junction != nullptr; }
  RoadJunctionArm const & Arm() const { return m_junction->m_arms[m_armIndex]; }
};

class RoadJunctions
{
public:
  using Links = std::vector<RoadJunctionLink>;
  static std::unique_ptr<RoadJunctions> Load(FilesContainerR const & container);
  static void Write(Writer & writer, std::vector<RoadJunction> const & junctions);
  Links Get(uint32_t featureId) const;

private:
  std::vector<RoadJunction> m_junctions;
  struct LinkIndex
  {
    uint32_t m_featureId;
    uint32_t m_junctionIndex;
    uint32_t m_armIndex;
    auto operator<=>(LinkIndex const &) const = default;
  };
  std::vector<LinkIndex> m_links;
};
}  // namespace feature
