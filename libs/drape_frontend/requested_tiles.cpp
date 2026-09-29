#include "drape_frontend/requested_tiles.hpp"

namespace df
{
void RequestedTiles::Set(ScreenBase const & screen, bool have3dBuildings, bool forceRequest, bool forceUserMarksRequest,
                         TTilesCollection && tiles)
{
  std::lock_guard<std::mutex> lock(m_mutex);
  for (auto const & key : m_lastTiles)
    if (!tiles.contains(key))
      m_retiredTiles.insert(key);
  m_lastTiles = tiles;
  m_tiles = std::move(tiles);
  m_screen = screen;
  m_have3dBuildings = have3dBuildings;
  // Several frames may replace the pending viewport before the backend consumes it.
  // Keep invalidations until Get() acknowledges them, including user marks.
  m_forceRequest |= forceRequest;
  m_forceUserMarksRequest |= forceUserMarksRequest;
}

TTilesCollection RequestedTiles::Get(ScreenBase & screen, bool & have3dBuildings, bool & forceRequest,
                                     bool & forceUserMarksRequest)
{
  std::lock_guard<std::mutex> lock(m_mutex);
  screen = m_screen;
  have3dBuildings = m_have3dBuildings;
  forceRequest = m_forceRequest;
  forceUserMarksRequest = m_forceUserMarksRequest;
  m_forceRequest = m_forceUserMarksRequest = false;
  // A tile may have disappeared and returned while backend coverage messages were coalesced.
  // Its frontend geometry can already be retired, so do not treat the backend's old cache as ready.
  for (auto const & key : m_retiredTiles)
    forceRequest |= m_tiles.contains(key);
  m_retiredTiles.clear();
  TTilesCollection tiles;
  m_tiles.swap(tiles);
  return tiles;
}

bool RequestedTiles::CheckTileKey(TileKey const & tileKey) const
{
  std::lock_guard<std::mutex> lock(m_mutex);
  return m_lastTiles.contains(tileKey);
}
}  // namespace df
