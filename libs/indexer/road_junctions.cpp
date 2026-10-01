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
template <class Source>
void ReadJunction(Source & source, uint8_t version, RoadJunction & junction)
{
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
  }
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
  return Load(container.GetMemoryRegion(ROAD_JUNCTIONS_FILE_TAG));
}

std::unique_ptr<RoadJunctions> RoadJunctions::Load(std::unique_ptr<MemoryRegion> data)
{
  auto result = std::make_unique<RoadJunctions>();
  result->m_data = std::move(data);
  MemReader bytes(result->m_data->ImmutableData(), result->m_data->Size());
  ReaderSource source(bytes);
  result->m_version = ReadPrimitiveFromSource<uint8_t>(source);
  CHECK_LESS_OR_EQUAL(result->m_version, 1, ());
  auto const count = ReadVarUint<uint32_t>(source);
  result->m_offsets.reserve(count);
  result->m_links.reserve(static_cast<size_t>(count) * 3);
  RoadJunction scratch;
  for (uint32_t j = 0; j < count; ++j)
  {
    result->m_offsets.push_back(source.Pos());
    ReadJunction(source, result->m_version, scratch);
    for (uint32_t i = 0; i < scratch.m_arms.size(); ++i)
      result->m_links.push_back({scratch.m_arms[i].m_featureId, j, i});
  }
  std::sort(result->m_links.begin(), result->m_links.end());
  return result;
}

RoadJunctions::Links RoadJunctions::Get(uint32_t featureId) const
{
  auto it = std::lower_bound(m_links.begin(), m_links.end(), featureId,
                             [](auto const & link, uint32_t id) { return link.m_featureId < id; });
  Links result;
  if (it == m_links.end() || it->m_featureId != featureId)
    return result;
  std::lock_guard lock(m_cacheMutex);
  for (; it != m_links.end() && it->m_featureId == featureId; ++it)
  {
    bool found;
    auto & cached = m_cache.Find(it->m_junctionIndex, found);
    if (!found)
    {
      auto junction = std::make_shared<RoadJunction>();
      auto const offset = m_offsets[it->m_junctionIndex];
      MemReader bytes(m_data->ImmutableData() + offset, m_data->Size() - offset);
      ReaderSource source(bytes);
      ReadJunction(source, m_version, *junction);
      cached = std::move(junction);
    }
    result.push_back({cached.get(), it->m_armIndex, cached});
  }
  return result;
}
}  // namespace feature
