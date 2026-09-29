#pragma once

#include "indexer/road_details.hpp"

struct OsmElement;

namespace generator
{
std::optional<feature::RoadDetails> ParseRoadDetails(OsmElement const & element);
}
