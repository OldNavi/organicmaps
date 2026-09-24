#pragma once

#include <cstdint>

namespace df
{
struct Hints
{
  // Zero disables the automotive FPS limit; other builds retain their default route-following pacing.
  int m_maxFps = 0;
  double m_renderScale = 1.0;
  uint32_t m_msaaSamples = 0;
  bool m_isFirstLaunch = false;
  bool m_isLaunchByDeepLink = false;
  bool m_screenshotMode = false;
  bool m_isPassiveNavigation = false;
  bool m_showPoi = true;
};
}  // namespace df
