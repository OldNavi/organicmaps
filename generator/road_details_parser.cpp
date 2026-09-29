#include "generator/road_details_parser.hpp"

#include "generator/osm_element.hpp"

#include "base/string_utils.hpp"

#include <algorithm>
#include <cmath>
#include <numeric>
#include <string_view>

namespace generator
{
namespace
{
using Details = feature::RoadDetails;

unsigned Count(std::string const & value)
{
  unsigned count = 0;
  return strings::to_uint(value, count) && count <= Details::kMaxLanes ? count : 0;
}

uint16_t Width(std::string value)
{
  strings::Trim(value);
  if (value.ends_with(" m"))
    value.resize(value.size() - 2);
  double meters = 0.0;
  if (!strings::to_double(value, meters) || !std::isfinite(meters) || meters < 0.5 || meters > 150)
    return 0;
  return static_cast<uint16_t>(std::lround(meters * 100));
}

std::vector<uint16_t> Widths(std::string const & value)
{
  std::vector<uint16_t> result;
  if (value.empty())
    return result;
  size_t begin = 0;
  do
  {
    auto const end = value.find('|', begin);
    result.push_back(Width(value.substr(begin, end - begin)));
    if (end == std::string::npos)
      break;
    begin = end + 1;
  }
  while (result.size() <= Details::kMaxLanes);
  return result;
}

void ApplyTurns(std::vector<Details::Lane> & lanes, size_t offset, size_t count, std::string const & value,
                bool reverse = false)
{
  if (value.empty())
    return;
  std::vector<uint16_t> turns;
  size_t begin = 0;
  do
  {
    auto const end = value.find('|', begin);
    auto const lane = value.substr(begin, end - begin);
    uint16_t mask = 0;
    for (strings::SimpleTokenizer token(lane, ";"); token; ++token)
      if (*token == "through")
        mask |= Details::Through;
      else if (*token == "left")
        mask |= Details::Left;
      else if (*token == "right")
        mask |= Details::Right;
      else if (*token == "slight_left")
        mask |= Details::SlightLeft;
      else if (*token == "slight_right")
        mask |= Details::SlightRight;
      else if (*token == "sharp_left")
        mask |= Details::SharpLeft;
      else if (*token == "sharp_right")
        mask |= Details::SharpRight;
      else if (*token == "reverse")
        mask |= Details::Reverse;
      else if (*token == "merge_to_left")
        mask |= Details::MergeLeft;
      else if (*token == "merge_to_right")
        mask |= Details::MergeRight;
    turns.push_back(mask);
    if (end == std::string::npos)
      break;
    begin = end + 1;
  }
  while (turns.size() <= Details::kMaxLanes);
  if (turns.size() != count)
    return;
  if (reverse)
    std::reverse(turns.begin(), turns.end());
  for (size_t i = 0; i < count; ++i)
    lanes[offset + i].m_turns = turns[i];
}

void ApplyWidths(std::vector<Details::Lane> & lanes, size_t offset, size_t count, std::string const & tag,
                 bool reverse = false)
{
  auto widths = Widths(tag);
  if (widths.size() != count)
    return;
  if (reverse)
    std::reverse(widths.begin(), widths.end());
  for (size_t i = 0; i < count; ++i)
    if (widths[i] >= 50 && widths[i] <= 1000)
      lanes[offset + i] = {widths[i], Details::WidthSource::Explicit};
}

void ApplyDesignations(std::vector<bool> & lanes, size_t offset, size_t count, std::string const & value,
                       bool reverse = false)
{
  if (value.empty())
    return;
  std::vector<std::string> values;
  size_t begin = 0;
  do
  {
    auto const end = value.find('|', begin);
    values.push_back(value.substr(begin, end - begin));
    strings::Trim(values.back());
    if (end == std::string::npos)
      break;
    begin = end + 1;
  }
  while (values.size() <= Details::kMaxLanes);
  if (values.size() != count)
    return;
  if (reverse)
    std::reverse(values.begin(), values.end());
  for (size_t i = 0; i < count; ++i)
    if (!values[i].empty())
      lanes[offset + i] = values[i] == "designated";
}

std::optional<int16_t> PlacementOffset(Details const & details, std::string const & value, bool reverse)
{
  auto const colon = value.find(':');
  if (colon == std::string::npos)
    return {};
  auto const lane = Count(value.substr(colon + 1));
  if (lane == 0 || lane > details.m_lanes.size() || details.m_inferredLaneCount)
    return {};
  auto const side = value.substr(0, colon);
  if (side != "left_of" && side != "middle_of" && side != "right_of")
    return {};
  double offset = -details.WidthMeters() * 50;
  for (size_t i = 0; i < lane; ++i)
  {
    double const width = details.m_lanes[reverse ? details.m_lanes.size() - 1 - i : i].m_widthCm;
    offset += width * (i + 1 < lane || side == "right_of" ? 1 : side == "middle_of" ? 0.5 : 0);
  }
  return static_cast<int16_t>(std::lround(offset * (reverse ? -1 : 1)));
}
}  // namespace

std::optional<Details> ParseRoadDetails(OsmElement const & element)
{
  auto const highway = element.GetTag("highway");
  constexpr std::string_view roads[] = {
      "motorway", "trunk",         "primary",       "secondary",  "tertiary",     "unclassified",   "residential",
      "service",  "living_street", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"};
  if (!element.IsWay() || std::find(std::begin(roads), std::end(roads), highway) == std::end(roads))
    return {};
  if (element.HasTag("area", "yes"))
    return {};

  Details details;
  details.m_roundabout = element.HasTag("junction", "roundabout");
  auto const oneway = element.GetTag("oneway");
  details.m_oneWay = oneway == "yes" || oneway == "1" || oneway == "-1" || oneway == "true" ||
                     (oneway.empty() && (highway == "motorway" || element.HasTag("junction", "roundabout")));
  details.m_markings = !element.HasTag("lane_markings", "no");
  auto count = Count(element.GetTag("lanes"));
  auto const forward = Count(element.GetTag("lanes:forward"));
  auto const backward = Count(element.GetTag("lanes:backward"));
  auto const shared = Count(element.GetTag("lanes:both_ways"));
  if (count == 0)
    count = details.m_oneWay ? (oneway == "-1" ? backward : forward) : forward + backward + shared;
  if (count > Details::kMaxLanes)
    return {};
  if (count == 0)
  {
    // Malformed explicit counts are not an instruction to infer a different count.
    if (element.HasTag("lanes") || element.HasTag("lanes:forward") || element.HasTag("lanes:backward"))
      return {};
    // Give adjacent roads a consistent physical width even without detailed lane mapping.
    // No lane divisions are inferred from the road class alone.
    bool const narrow = highway == "service" || highway == "living_street" || highway.ends_with("_link") ||
                        (details.m_oneWay && highway != "trunk" && highway != "motorway");
    count = narrow ? 1 : 2;
    details.m_markings = false;
    details.m_inferredLaneCount = true;
  }
  details.m_lanes.resize(count);

  ApplyWidths(details.m_lanes, 0, count, element.GetTag("width:lanes"));
  if (details.m_oneWay)
  {
    ApplyWidths(details.m_lanes, 0, count,
                element.GetTag(oneway == "-1" ? "width:lanes:backward" : "width:lanes:forward"));
  }
  else if (forward + backward + shared == count)
  {
    details.m_directionalGroups = true;
    details.m_backwardLanes = backward;
    details.m_sharedLanes = shared;
    ApplyWidths(details.m_lanes, 0, backward, element.GetTag("width:lanes:backward"), true);
    ApplyWidths(details.m_lanes, backward, shared, element.GetTag("width:lanes:both_ways"));
    ApplyWidths(details.m_lanes, backward + shared, forward, element.GetTag("width:lanes:forward"));
  }

  // Total road width may include parking, shoulders or cycle lanes: do not assign those to traffic lanes.
  bool usableTotalWidth = true;
  for (auto const & tag : element.Tags())
    if ((tag.m_key.starts_with("parking:") || tag.m_key.starts_with("cycleway") || tag.m_key.starts_with("shoulder")) &&
        tag.m_value != "no" && tag.m_value != "none" && tag.m_value != "no_stopping")
      usableTotalWidth = false;
  unsigned const total = usableTotalWidth ? Width(element.GetTag("width")) : 0;
  unsigned knownWidth = 0, unknownCount = 0;
  for (auto const & lane : details.m_lanes)
    if (lane.m_source == Details::WidthSource::Explicit)
      knownWidth += lane.m_widthCm;
    else
      ++unknownCount;
  if (unknownCount != 0 && total > knownWidth)
  {
    auto const width = (total - knownWidth) / unknownCount;
    if (width >= 50 && width <= 1000)
      for (auto & lane : details.m_lanes)
        if (lane.m_source != Details::WidthSource::Explicit)
          lane = {static_cast<uint16_t>(width), Details::WidthSource::TotalWidth};
  }
  if (details.m_oneWay)
  {
    ApplyTurns(details.m_lanes, 0, count, element.GetTag("turn:lanes"));
    ApplyTurns(details.m_lanes, 0, count,
               element.GetTag(oneway == "-1" ? "turn:lanes:backward" : "turn:lanes:forward"));
  }
  else if (details.m_directionalGroups)
  {
    ApplyTurns(details.m_lanes, 0, backward, element.GetTag("turn:lanes:backward"), true);
    ApplyTurns(details.m_lanes, backward, shared, element.GetTag("turn:lanes:both_ways"));
    ApplyTurns(details.m_lanes, backward + shared, forward, element.GetTag("turn:lanes:forward"));
  }
  for (auto const prefix : {"psv:lanes", "bus:lanes"})
  {
    std::vector<bool> designated(count, false);
    ApplyDesignations(designated, 0, count, element.GetTag(prefix));
    if (details.m_oneWay)
      ApplyDesignations(designated, 0, count,
                        element.GetTag(std::string(prefix) + (oneway == "-1" ? ":backward" : ":forward")));
    else if (details.m_directionalGroups)
    {
      ApplyDesignations(designated, 0, backward, element.GetTag(std::string(prefix) + ":backward"), true);
      ApplyDesignations(designated, backward + shared, forward, element.GetTag(std::string(prefix) + ":forward"));
    }
    for (size_t lane = 0; lane < count; ++lane)
      details.m_lanes[lane].m_publicTransport |= designated[lane];
  }

  auto placement = element.GetTag("placement");
  auto start = element.GetTag("placement:start"), end = element.GetTag("placement:end");
  for (auto const & tag : element.Tags())
    if (tag.m_key.starts_with("placement:") && tag.m_key != "placement:start" && tag.m_key != "placement:end" &&
        !tag.m_value.empty())
      return {};  // Direction-specific two-way carriageways need their own reference frames.
  if (!placement.empty() || !start.empty() || !end.empty())
  {
    if (!details.m_oneWay)
      return {};
    details.m_placementTransition = placement == "transition";
    if (start.empty())
      start = details.m_placementTransition ? "" : placement;
    if (end.empty())
      end = details.m_placementTransition ? "" : placement;
    if (!details.m_placementTransition && (start.empty() || end.empty()))
      return {};
    bool const reverse = oneway == "-1";
    auto const first = start.empty() ? std::optional<int16_t>(0) : PlacementOffset(details, start, reverse);
    auto const last = end.empty() ? std::optional<int16_t>(0) : PlacementOffset(details, end, reverse);
    if (!first || !last)
      return {};
    details.m_startOffsetCm = reverse ? *last : *first;
    details.m_endOffsetCm = reverse ? *first : *last;
    if (details.m_placementTransition)
      details.m_markings = false;
  }
  return details;
}
}  // namespace generator
