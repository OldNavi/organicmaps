#include "drape_frontend/user_mark_badge_layout.hpp"

#include "geometry/spatial_hash_grid.hpp"

#include <algorithm>

namespace df
{
void PlaceUserMarkBadges(std::vector<UserMarkFootprint> const & symbols, std::vector<UserMarkFootprint> & badges,
                         std::vector<kml::MarkId> & visible)
{
  visible.clear();
  if (badges.empty())
    return;
  double cellSize = 1;
  for (auto const * items : {&symbols, static_cast<std::vector<UserMarkFootprint> const *>(&badges)})
    for (auto const & item : *items)
      cellSize = std::max({cellSize, item.m_rect.SizeX(), item.m_rect.SizeY()});
  m2::PointHashMap<UserMarkFootprint> occupied(cellSize);
  for (auto const & symbol : symbols)
    occupied.Emplace(symbol.m_rect.Center(), symbol);
  std::sort(badges.begin(), badges.end(), [](auto const & a, auto const & b) { return a.m_id < b.m_id; });
  for (auto const & badge : badges)
  {
    bool intersects = false;
    occupied.ForEachPoint(badge.m_rect.Center(), [&](UserMarkFootprint const & other)
    { intersects |= other.m_id != badge.m_id && other.m_rect.IsIntersect(badge.m_rect); });
    if (intersects)
      continue;
    visible.push_back(badge.m_id);
    occupied.Emplace(badge.m_rect.Center(), badge);
  }
}
}  // namespace df
