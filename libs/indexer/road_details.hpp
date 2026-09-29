#pragma once

#include "indexer/road_junctions.hpp"

#include "geometry/rect2d.hpp"

#include "coding/files_container.hpp"
#include "coding/map_uint32_to_val.hpp"
#include "coding/read_write_utils.hpp"
#include "coding/varint.hpp"

#include <cstdint>
#include <memory>
#include <optional>
#include <vector>

namespace feature
{
struct RoadDetails
{
  static constexpr uint8_t kMaxLanes = 32;
  static constexpr uint16_t kTurnMask = 1023;
  static constexpr uint16_t kPublicTransportFlag = 1024;
  enum class WidthSource : uint8_t
  {
    Explicit,
    TotalWidth,
    Default
  };
  enum Turn : uint16_t
  {
    Through = 1,
    Left = 2,
    Right = 4,
    SlightLeft = 8,
    SlightRight = 16,
    SharpLeft = 32,
    SharpRight = 64,
    Reverse = 128,
    MergeLeft = 256,
    MergeRight = 512
  };
  struct Lane
  {
    uint16_t m_widthCm = 350;
    WidthSource m_source = WidthSource::Default;
    uint16_t m_turns = 0;
    bool m_publicTransport = false;
    bool operator==(Lane const &) const = default;
  };

  // Left to right across the feature. Before finalization, directional groups use right-hand order.
  std::vector<Lane> m_lanes;
  bool m_oneWay = false;
  bool m_markings = true;
  bool m_directionalGroups = false;
  bool m_leftHand = false;
  bool m_roundabout = false;
  bool m_inferredLaneCount = false;
  bool m_placementTransition = false;
  int16_t m_startOffsetCm = 0, m_endOffsetCm = 0;  // Positive to the left of the normalized feature direction.
  uint8_t m_backwardLanes = 0;
  uint8_t m_sharedLanes = 0;

  bool operator==(RoadDetails const &) const = default;
  double WidthMeters() const;
  m2::RectD BoundsWithRoadWidth(m2::RectD bounds) const;
  void ArrangeForDrivingSide(bool leftHand);

  template <class Sink>
  void Write(Sink & sink, uint8_t version = 2) const
  {
    CHECK(!m_lanes.empty() && m_lanes.size() <= kMaxLanes, (m_lanes.size()));
    WriteToSink(sink, static_cast<uint8_t>(m_lanes.size()));
    WriteToSink(sink,
                static_cast<uint8_t>(m_oneWay | (m_markings << 1) | (m_directionalGroups << 2) | (m_leftHand << 3) |
                                     (m_roundabout << 4) | (m_inferredLaneCount << 5) | (m_placementTransition << 6)));
    WriteToSink(sink, m_backwardLanes);
    WriteToSink(sink, m_sharedLanes);
    for (auto const & lane : m_lanes)
    {
      WriteVarUint(sink, lane.m_widthCm);
      WriteToSink(sink, static_cast<uint8_t>(lane.m_source));
      if (version >= 1)
        WriteVarUint(sink, static_cast<uint16_t>(lane.m_turns | (lane.m_publicTransport ? kPublicTransportFlag : 0)));
    }
    if (version >= 2)
    {
      WriteVarInt(sink, m_startOffsetCm);
      WriteVarInt(sink, m_endOffsetCm);
    }
  }

  template <class Source>
  void Read(Source & source, uint8_t version = 2)
  {
    auto const count = ReadPrimitiveFromSource<uint8_t>(source);
    CHECK(count > 0 && count <= kMaxLanes, (count));
    auto const flags = ReadPrimitiveFromSource<uint8_t>(source);
    m_oneWay = flags & 1;
    m_markings = flags & 2;
    m_directionalGroups = flags & 4;
    m_leftHand = flags & 8;
    m_roundabout = flags & 16;
    m_inferredLaneCount = flags & 32;
    m_placementTransition = flags & 64;
    m_backwardLanes = ReadPrimitiveFromSource<uint8_t>(source);
    m_sharedLanes = ReadPrimitiveFromSource<uint8_t>(source);
    CHECK_LESS_OR_EQUAL(m_backwardLanes + m_sharedLanes, count, ());
    m_lanes.resize(count);
    for (auto & lane : m_lanes)
    {
      lane.m_widthCm = base::asserted_cast<uint16_t>(ReadVarUint<uint32_t>(source));
      lane.m_source = static_cast<WidthSource>(ReadPrimitiveFromSource<uint8_t>(source));
      auto const laneFlags = version >= 1 ? base::asserted_cast<uint16_t>(ReadVarUint<uint32_t>(source)) : 0;
      lane.m_turns = laneFlags & kTurnMask;
      lane.m_publicTransport = laneFlags & kPublicTransportFlag;
      CHECK_GREATER(lane.m_widthCm, 0, ());
      CHECK_LESS_OR_EQUAL(static_cast<uint8_t>(lane.m_source), static_cast<uint8_t>(WidthSource::Default), ());
    }
    m_startOffsetCm = version >= 2 ? base::asserted_cast<int16_t>(ReadVarInt<int32_t>(source)) : 0;
    m_endOffsetCm = version >= 2 ? base::asserted_cast<int16_t>(ReadVarInt<int32_t>(source)) : 0;
  }
};

// Optional MWM section. The immutable index is shared by feature readers; values are decoded on tile workers.
class RoadDetailsReader
{
public:
  static std::unique_ptr<RoadDetailsReader> Load(FilesContainerR const & container);
  std::optional<RoadDetails> Get(uint32_t featureId) const;
  RoadJunctions::Links GetJunctions(uint32_t id) const
  {
    return m_junctions ? m_junctions->Get(id) : RoadJunctions::Links{};
  }
  uint64_t Count() const { return m_index->Count(); }

private:
  std::unique_ptr<RoadJunctions> m_junctions;
  std::vector<uint8_t> m_data;
  std::unique_ptr<Reader> m_reader;
  std::unique_ptr<MapUint32ToValue<RoadDetails>> m_index;
};

class RoadDetailsBuilder
{
public:
  void Put(uint32_t featureId, RoadDetails const & details)
  {
    m_builder.Put(featureId, details);
    ++m_count;
  }
  bool Empty() const { return m_count == 0; }
  void Freeze(Writer & writer) const;

private:
  MapUint32ToValueBuilder<RoadDetails> m_builder;
  size_t m_count = 0;
};
}  // namespace feature
