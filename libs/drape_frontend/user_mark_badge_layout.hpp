#pragma once

#include "geometry/rect2d.hpp"
#include "kml/types.hpp"

#include <vector>

namespace df
{
struct UserMarkFootprint
{
  kml::MarkId m_id;
  m2::RectD m_rect;
};

// Symbols remain visible. A badge cannot cover another symbol or an accepted badge.
void PlaceUserMarkBadges(std::vector<UserMarkFootprint> const & symbols, std::vector<UserMarkFootprint> & badges,
                         std::vector<kml::MarkId> & visible);
}  // namespace df
