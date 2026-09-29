#include "indexer/road_junctions.hpp"

#include "coding/read_write_utils.hpp"
#include "coding/varint.hpp"

#include "defines.hpp"

#include <algorithm>
#include <bit>
#include <numeric>

namespace feature
{
namespace
{
void WriteCoordinate(Writer & writer, double value)
{
  WriteToSink(writer, std::bit_cast<uint64_t>(value));
}
template <class Source>
double ReadCoordinate(Source & source)
{
  return std::bit_cast<double>(ReadPrimitiveFromSource<uint64_t>(source));
}
}  // namespace

double RoadJunctionArm::WidthMeters() const
{
  return std::accumulate(m_widthsCm.begin(), m_widthsCm.end(), 0.0) * 0.01;
}

m2::RectD RoadJunction::Bounds() const
{
  double radius = 0;
  for (auto const & arm : m_arms)
    radius = std::max(
        radius, (arm.m_position - m_center).Length() + arm.m_normalAwayPerMeter.Length() * (arm.WidthMeters() / 2 + 3));
  // Include the boundary curves' control points, not just their cross-sections.
  radius *= 2;
  return {m_center.x - radius, m_center.y - radius, m_center.x + radius, m_center.y + radius};
}

void RoadJunctions::Write(Writer & writer, std::vector<RoadJunction> const & junctions)
{
  WriteToSink(writer, uint8_t{1});
  WriteVarUint(writer, static_cast<uint32_t>(junctions.size()));
  for (auto const & junction : junctions)
  {
    WriteVarUint(writer, junction.m_ownerFeatureId);
    WriteToSink(writer, junction.m_continuationA);
    WriteToSink(writer, junction.m_continuationB);
    WriteCoordinate(writer, junction.m_center.x);
    WriteCoordinate(writer, junction.m_center.y);
    WriteVarUint(writer, static_cast<uint32_t>(junction.m_arms.size()));
    for (auto const & arm : junction.m_arms)
    {
      WriteVarUint(writer, arm.m_featureId);
      WriteToSink(writer, static_cast<uint8_t>(arm.m_forward | (arm.m_oneWay << 1) | (arm.m_markings << 2) |
                                               (arm.m_featureEndpoint << 3)));
      WriteCoordinate(writer, arm.m_nodeDistance);
      WriteCoordinate(writer, arm.m_cutDistance);
      WriteCoordinate(writer, arm.m_position.x);
      WriteCoordinate(writer, arm.m_position.y);
      WriteCoordinate(writer, arm.m_directionAway.x);
      WriteCoordinate(writer, arm.m_directionAway.y);
      WriteCoordinate(writer, arm.m_nodeDirectionAway.x);
      WriteCoordinate(writer, arm.m_nodeDirectionAway.y);
      WriteCoordinate(writer, arm.m_normalAwayPerMeter.x);
      WriteCoordinate(writer, arm.m_normalAwayPerMeter.y);
      WriteVarUint(writer, static_cast<uint32_t>(arm.m_widthsCm.size()));
      CHECK_EQUAL(arm.m_widthsCm.size(), arm.m_turns.size(), ());
      for (size_t i = 0; i < arm.m_widthsCm.size(); ++i)
      {
        WriteVarUint(writer, arm.m_widthsCm[i]);
        WriteVarUint(writer, arm.m_turns[i]);
      }
    }
  }
}

std::unique_ptr<RoadJunctions> RoadJunctions::Load(FilesContainerR const & container)
{
  if (!container.IsExist(ROAD_JUNCTIONS_FILE_TAG))
    return {};
  // Decode a sequential section from one read, without millions of small page-cache lookups.
  auto const bytes = container.GetMemoryRegion(ROAD_JUNCTIONS_FILE_TAG);
  MemReader reader(bytes->ImmutableData(), bytes->Size());
  ReaderSource source(reader);
  auto const version = ReadPrimitiveFromSource<uint8_t>(source);
  CHECK_LESS_OR_EQUAL(version, 1, ());
  auto result = std::make_unique<RoadJunctions>();
  result->m_junctions.resize(ReadVarUint<uint32_t>(source));
  result->m_links.reserve(result->m_junctions.size() * 3);
  for (uint32_t j = 0; j < result->m_junctions.size(); ++j)
  {
    auto & junction = result->m_junctions[j];
    junction.m_ownerFeatureId = ReadVarUint<uint32_t>(source);
    if (version >= 1)
    {
      junction.m_continuationA = ReadPrimitiveFromSource<uint8_t>(source);
      junction.m_continuationB = ReadPrimitiveFromSource<uint8_t>(source);
    }
    junction.m_center.x = ReadCoordinate(source);
    junction.m_center.y = ReadCoordinate(source);
    junction.m_arms.resize(ReadVarUint<uint32_t>(source));
    for (size_t i = 0; i < junction.m_arms.size(); ++i)
    {
      auto & arm = junction.m_arms[i];
      arm.m_featureId = ReadVarUint<uint32_t>(source);
      auto const flags = ReadPrimitiveFromSource<uint8_t>(source);
      arm.m_forward = flags & 1;
      arm.m_oneWay = flags & 2;
      arm.m_markings = flags & 4;
      arm.m_featureEndpoint = flags & 8;
      arm.m_nodeDistance = ReadCoordinate(source);
      arm.m_cutDistance = ReadCoordinate(source);
      arm.m_position.x = ReadCoordinate(source);
      arm.m_position.y = ReadCoordinate(source);
      arm.m_directionAway.x = ReadCoordinate(source);
      arm.m_directionAway.y = ReadCoordinate(source);
      if (version >= 1)
      {
        arm.m_nodeDirectionAway.x = ReadCoordinate(source);
        arm.m_nodeDirectionAway.y = ReadCoordinate(source);
      }
      else
        arm.m_nodeDirectionAway = arm.m_directionAway;
      arm.m_normalAwayPerMeter.x = ReadCoordinate(source);
      arm.m_normalAwayPerMeter.y = ReadCoordinate(source);
      arm.m_widthsCm.resize(ReadVarUint<uint32_t>(source));
      arm.m_turns.resize(arm.m_widthsCm.size());
      for (size_t j = 0; j < arm.m_widthsCm.size(); ++j)
      {
        arm.m_widthsCm[j] = base::asserted_cast<uint16_t>(ReadVarUint<uint32_t>(source));
        arm.m_turns[j] = base::asserted_cast<uint16_t>(ReadVarUint<uint32_t>(source));
      }
      result->m_links.push_back({arm.m_featureId, j, base::asserted_cast<uint32_t>(i)});
    }
  }
  // A flat immutable lookup avoids a hash node and a separate vector allocation per road.
  std::sort(result->m_links.begin(), result->m_links.end());
  return result;
}

RoadJunctions::Links RoadJunctions::Get(uint32_t featureId) const
{
  auto it = std::lower_bound(m_links.begin(), m_links.end(), featureId,
                             [](auto const & link, uint32_t id) { return link.m_featureId < id; });
  Links result;
  for (; it != m_links.end() && it->m_featureId == featureId; ++it)
    result.push_back({&m_junctions[it->m_junctionIndex], it->m_armIndex});
  return result;
}
}  // namespace feature
