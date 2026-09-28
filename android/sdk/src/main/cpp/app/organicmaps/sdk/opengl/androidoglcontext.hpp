#pragma once

#include "drape/gl_includes.hpp"
#include "drape/oglcontext.hpp"

#ifdef OMIM_AUTO
#include "drape/frame_cadence.hpp"
#endif

#include <atomic>

namespace android
{
class AndroidOGLContext : public dp::OGLContext
{
public:
  AndroidOGLContext(EGLDisplay display, EGLSurface surface, EGLConfig config, AndroidOGLContext * contextToShareWith);
  ~AndroidOGLContext();

  void MakeCurrent() override;
  void DoneCurrent() override;
  void Present() override;
  void SetFramebuffer(ref_ptr<dp::BaseFramebuffer> framebuffer) override;
  void SetRenderingEnabled(bool enabled) override;
  void SetPresentAvailable(bool available) override;
  bool Validate() override;
#ifdef OMIM_AUTO
  bool WaitForFrame(double minFrameTime, std::function<bool()> const & cancelled) override;
#endif

  void SetSurface(EGLSurface surface);
  void ResetSurface();

  void ClearCurrent();

private:
  // {@ Owned by Context
  EGLContext m_nativeContext;
  // @}

  // {@ Owned by Factory
  EGLSurface m_surface;
  EGLDisplay m_display;
  // @}

  std::atomic<bool> m_presentAvailable;
#ifdef OMIM_AUTO
  dp::FrameCadence m_frameCadence;
#endif
};
}  // namespace android
