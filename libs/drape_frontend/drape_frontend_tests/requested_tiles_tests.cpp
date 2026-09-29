#include "testing/testing.hpp"

#include "drape_frontend/requested_tiles.hpp"

#include <atomic>
#include <thread>

namespace requested_tiles_tests
{
UNIT_TEST(RequestedTiles_ReenteredCellInvalidatesCoalescedCoverage)
{
  df::RequestedTiles requests;
  ScreenBase screen;
  bool buildings, force, marks;
  df::TTilesCollection const first{df::TileKey(0, 0, 18)};
  df::TTilesCollection const second{df::TileKey(1, 0, 18)};
  requests.Set(screen, false, false, false, df::TTilesCollection(first));
  TEST(requests.Get(screen, buildings, force, marks) == first, ());
  TEST(!force, ());
  requests.Set(screen, false, false, false, df::TTilesCollection(second));
  requests.Set(screen, false, false, false, df::TTilesCollection(first));
  TEST(requests.Get(screen, buildings, force, marks) == first, ());
  TEST(force, ());
  TEST(requests.CheckTileKey(*first.begin()), ());
  TEST(!requests.CheckTileKey(*second.begin()), ());

  requests.Set(screen, false, false, false, df::TTilesCollection(first));
  requests.Get(screen, buildings, force, marks);
  TEST(!force, ("Reentry invalidation must be consumed exactly once"));
}

UNIT_TEST(RequestedTiles_CoalescedFramesPreserveInvalidations)
{
  df::RequestedTiles requests;
  ScreenBase screen;
  bool buildings, force, marks;
  df::TTilesCollection const tiles{df::TileKey(0, 0, 18)};
  requests.Set(screen, false, false, true, df::TTilesCollection(tiles));
  requests.Set(screen, false, true, false, df::TTilesCollection(tiles));
  requests.Set(screen, true, false, false, df::TTilesCollection(tiles));
  TEST(requests.Get(screen, buildings, force, marks) == tiles, ());
  TEST(force && marks && buildings, ("The last viewport must retain earlier invalidations"));
  TEST(requests.Get(screen, buildings, force, marks).empty(), ());
  TEST(!force && !marks, ("Consume invalidations exactly once"));
  TEST(requests.CheckTileKey(*tiles.begin()), ("Consuming a request must retain its coverage"));
}

UNIT_TEST(RequestedTiles_OneWayPanDoesNotForceReread)
{
  df::RequestedTiles requests;
  ScreenBase screen;
  bool buildings, force, marks;
  requests.Set(screen, false, false, false, {df::TileKey(0, 0, 18)});
  requests.Get(screen, buildings, force, marks);
  requests.Set(screen, false, false, false, {df::TileKey(1, 0, 18)});
  requests.Set(screen, false, false, false, {df::TileKey(2, 0, 18)});
  TEST(!requests.CheckTileKey(df::TileKey(0, 0, 18)), ());
  requests.Get(screen, buildings, force, marks);
  TEST(!force, ("Only returning cells require a coalesced-coverage invalidation"));
}

UNIT_TEST(RequestedTiles_ConcurrentViewportSnapshot)
{
  df::RequestedTiles requests;
  ScreenBase screens[2];
  screens[0].OnSize(0, 0, 256, 256);
  screens[0].SetFromRect(m2::AnyRectD(m2::RectD(0, 0, 1, 1)));
  screens[1].OnSize(0, 0, 512, 512);
  screens[1].SetFromRect(m2::AnyRectD(m2::RectD(1, 1, 2, 2)));
  std::atomic<bool> done = false;
  std::thread producer([&]
  {
    for (int i = 0; i < 10000; ++i)
    {
      int const index = i % 2;
      requests.Set(screens[index], index == 1, false, false, {df::TileKey(index, 0, 18)});
    }
    done = true;
  });
  bool consistent = true;
  size_t consumed = 0;
  for (;;)
  {
    bool const last = done.load();
    ScreenBase screen;
    bool buildings, force, marks;
    auto const tiles = requests.Get(screen, buildings, force, marks);
    if (!tiles.empty())
    {
      ++consumed;
      int const index = tiles.begin()->m_x;
      consistent &= screen == screens[index] && buildings == (index == 1);
    }
    if (last)
      break;
  }
  producer.join();
  TEST_GREATER(consumed, 0, ());
  TEST(consistent, ("Tile keys and viewport parameters must come from the same request"));
}
}  // namespace requested_tiles_tests
