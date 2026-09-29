#include "indexer/road_details.hpp"

#include "geometry/mercator.hpp"

#include "defines.hpp"

#include <algorithm>
#include <numeric>

namespace feature
{
namespace
{
uint64_t constexpr kVersion = 2;
}

double RoadDetails::WidthMeters() const
{
  return std::accumulate(m_lanes.begin(), m_lanes.end(), 0.0,
                         [](double width, Lane const & lane) { return width + lane.m_widthCm * 0.01; });
}

m2::RectD RoadDetails::BoundsWithRoadWidth(m2::RectD bounds) const
{
  // The renderer limits miter length to twice the half-width. Also retain room for the casing.
  auto const padding = mercator::MetersToXY(
      bounds.Center().x, mercator::YToLat(std::max(std::abs(bounds.minY()), std::abs(bounds.maxY()))),
      WidthMeters() + 0.02 * std::max(std::abs(m_startOffsetCm), std::abs(m_endOffsetCm)) + 5.0);
  bounds.Inflate(padding.SizeX() / 2, padding.SizeY() / 2);
  return bounds;
}

void RoadDetails::ArrangeForDrivingSide(bool leftHand)
{
  m_leftHand = leftHand;
  if (m_directionalGroups && leftHand)
  {
    auto const b = m_lanes.begin() + m_backwardLanes;
    auto const f = b + m_sharedLanes;
    std::vector<Lane> reordered(f, m_lanes.end());
    reordered.insert(reordered.end(), b, f);
    reordered.insert(reordered.end(), m_lanes.begin(), b);
    m_lanes.swap(reordered);
  }
  m_directionalGroups = false;
}

std::unique_ptr<RoadDetailsReader> RoadDetailsReader::Load(FilesContainerR const & container)
{
  if (!container.IsExist(ROAD_DETAILS_FILE_TAG))
    return {};
  auto reader = container.GetReader(ROAD_DETAILS_FILE_TAG);
  ReaderSource source(reader);
  auto const version = ReadPrimitiveFromSource<uint64_t>(source);
  CHECK_LESS_OR_EQUAL(version, kVersion, ());
  auto result = std::make_unique<RoadDetailsReader>();
  // FileReader's cache is mutable. Tile workers share only immutable bytes, never its cache.
  result->m_data.resize(reader.Size() - sizeof(kVersion));
  reader.Read(sizeof(kVersion), result->m_data.data(), result->m_data.size());
  result->m_reader = std::make_unique<MemReader>(result->m_data.data(), result->m_data.size());
  result->m_index = MapUint32ToValue<RoadDetails>::Load(
      *result->m_reader, [version](NonOwningReaderSource & input, uint32_t upperSize, std::vector<RoadDetails> & values)
  {
    for (uint32_t i = 0; i < upperSize && input.Size() > 0; ++i)
      values.emplace_back().Read(input, version);
  });
  CHECK(result->m_index, ());
  result->m_junctions = RoadJunctions::Load(container);
  return result;
}

std::optional<RoadDetails> RoadDetailsReader::Get(uint32_t featureId) const
{
  RoadDetails details;
  if (m_index->GetThreadsafe(featureId, details))
    return details;
  return {};
}

void RoadDetailsBuilder::Freeze(Writer & writer) const
{
  WriteToSink(writer, kVersion);
  m_builder.Freeze(writer, [](auto & sink, auto begin, auto end)
  {
    for (auto it = begin; it != end; ++it)
      it->Write(sink);
  }, 8 /* block size: keep random tile reads small */);
}
}  // namespace feature
