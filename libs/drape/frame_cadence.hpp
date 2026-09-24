#pragma once

#ifdef OMIM_AUTO

#include <cstdint>

namespace dp
{
// Select display ticks without accumulating render time or replaying missed frames.
class FrameCadence
{
public:
  bool ShouldRender(int64_t vsyncNs, int64_t intervalNs)
  {
    // Display timestamps and a nominal FPS period may differ by a few microseconds.
    int64_t constexpr kToleranceNs = 500000;
    if (m_intervalNs != intervalNs || m_nextNs < 0 || vsyncNs + intervalNs < m_nextNs)
    {
      m_intervalNs = intervalNs;
      m_nextNs = vsyncNs;
    }
    if (vsyncNs + kToleranceNs < m_nextNs)
      return false;

    auto const elapsed = vsyncNs + kToleranceNs - m_nextNs;
    m_nextNs += (elapsed / intervalNs + 1) * intervalNs;
    return true;
  }

  void Reset() { m_nextNs = -1; }

private:
  int64_t m_intervalNs = 0;
  int64_t m_nextNs = -1;
};
}  // namespace dp

#endif
