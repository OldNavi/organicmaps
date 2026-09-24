#pragma once

#ifdef OMIM_AUTO
#include <chrono>
#include <optional>

namespace feature
{
class TypesHolder;
}

namespace df
{
class DrivingPoiPolicy
{
public:
  using Clock = std::chrono::steady_clock;
  bool Update(double speedMps, Clock::time_point now)
  {
    bool const previous = m_driving;
    if (speedMps >= 10.0 / 3.6)
    {
      m_driving = true;
      m_stoppedSince.reset();
    }
    else if (m_driving && speedMps >= 0 && speedMps <= 3.0 / 3.6)
    {
      if (!m_stoppedSince)
        m_stoppedSince = now;
      // Short stops must not repeatedly invalidate every visible tile.
      if (now - *m_stoppedSince >= std::chrono::minutes(1))
        m_driving = false;
    }
    else
      m_stoppedSince.reset();
    return previous != m_driving;
  }
  bool IsDriving() const { return m_driving; }

private:
  bool m_driving = false;
  std::optional<Clock::time_point> m_stoppedSince;
};

bool IsDrivingPoi(feature::TypesHolder const & types);
}  // namespace df
#endif
