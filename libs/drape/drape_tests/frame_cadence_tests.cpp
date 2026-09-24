#include "testing/testing.hpp"

#include "drape/frame_cadence.hpp"

#ifdef OMIM_AUTO

namespace
{
UNIT_TEST(FrameCadence_DisplayRates)
{
  for (int const fps : {20, 24, 30})
  {
    dp::FrameCadence cadence;
    int rendered = 0;
    for (int64_t tick = 0; tick < 600; ++tick)
      rendered += cadence.ShouldRender(tick * 1000000000 / 60, 1000000000 / fps);
    TEST_EQUAL(rendered, fps * 10, (fps));
  }
}

UNIT_TEST(FrameCadence_FractionalRefreshRate)
{
  dp::FrameCadence cadence;
  int rendered = 0;
  for (int64_t tick = 0; tick < 600; ++tick)
    rendered += cadence.ShouldRender(tick * 16683333, 33333333);
  TEST_ALMOST_EQUAL_ABS(rendered, 300, 1, ());
}

UNIT_TEST(FrameCadence_SkipsMissedFrames)
{
  dp::FrameCadence cadence;
  TEST(cadence.ShouldRender(0, 50000000), ());
  TEST(!cadence.ShouldRender(16666667, 50000000), ());
  TEST(cadence.ShouldRender(1000000000, 50000000), ());
  TEST(!cadence.ShouldRender(1000000000, 50000000), ());
  TEST(!cadence.ShouldRender(1016666667, 50000000), ());
  TEST(cadence.ShouldRender(1050000000, 50000000), ());
}

UNIT_TEST(FrameCadence_IndependentDisplaysAndReset)
{
  dp::FrameCadence main, cluster;
  TEST(main.ShouldRender(0, 33333333), ());
  TEST(cluster.ShouldRender(0, 50000000), ());
  TEST(main.ShouldRender(33333333, 33333333), ());
  TEST(!cluster.ShouldRender(33333333, 50000000), ());
  cluster.Reset();
  TEST(cluster.ShouldRender(33333333, 50000000), ());
  TEST(cluster.ShouldRender(40000000, 16666667), ());
}
}  // namespace

#endif
