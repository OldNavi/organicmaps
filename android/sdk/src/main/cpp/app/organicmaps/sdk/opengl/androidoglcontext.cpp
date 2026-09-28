#include "androidoglcontext.hpp"
#include "android_gl_utils.hpp"

#include "base/assert.hpp"
#include "base/logging.hpp"
#include "base/src_point.hpp"

#ifdef OMIM_AUTO
#include <android/choreographer.h>
#include <android/looper.h>
#include <dlfcn.h>

#include <chrono>
#endif

namespace android
{

#ifdef OMIM_AUTO
namespace
{
struct DisplayClock
{
  using GetInstance = AChoreographer * (*)();
  using PostCallback = void (*)(AChoreographer *, AChoreographer_frameCallback64, void *);

  // Resolve API 29 functions dynamically: the standard SDK still supports API 21.
  GetInstance const m_getInstance = reinterpret_cast<GetInstance>(dlsym(RTLD_DEFAULT, "AChoreographer_getInstance"));
  PostCallback const m_postCallback =
      reinterpret_cast<PostCallback>(dlsym(RTLD_DEFAULT, "AChoreographer_postFrameCallback64"));
};

struct VSyncState
{
  AChoreographer * m_choreographer = nullptr;
  bool m_pending = false;
  int64_t m_timestampNs = 0;

  static void OnFrame(int64_t timestampNs, void * data)
  {
    auto & state = *static_cast<VSyncState *>(data);
    state.m_timestampNs = timestampNs;
    state.m_pending = false;
  }
};
}  // namespace

bool AndroidOGLContext::WaitForFrame(double minFrameTime, std::function<bool()> const & cancelled)
{
  static DisplayClock const clock;
  if (!clock.m_getInstance || !clock.m_postCallback)
    return false;

  // A posted callback cannot be removed. Its state belongs to the render thread,
  // not the context, so pausing or destroying a surface cannot leave a dangling callback.
  thread_local VSyncState state;
  if (!state.m_choreographer)
  {
    ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    state.m_choreographer = clock.m_getInstance();
    if (!state.m_choreographer)
      return false;
    LOG(LINFO, ("Automotive frame pacing uses Choreographer"));
  }

  auto const intervalNs = static_cast<int64_t>(minFrameTime * 1e9);
  while (m_presentAvailable && !cancelled())
  {
    if (!state.m_pending)
    {
      state.m_pending = true;
      clock.m_postCallback(state.m_choreographer, &VSyncState::OnFrame, &state);
    }
    auto const deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(100);
    while (state.m_pending)
    {
      // Polling sleeps in the looper; the short bound also serves pause/destroy handshakes.
      ALooper_pollOnce(8, nullptr, nullptr, nullptr);
      if (!m_presentAvailable || cancelled())
      {
        m_frameCadence.Reset();
        return true;
      }
      if (std::chrono::steady_clock::now() >= deadline)
      {
        m_frameCadence.Reset();
        return false;
      }
    }
    if (m_frameCadence.ShouldRender(state.m_timestampNs, intervalNs))
      return true;
  }
  m_frameCadence.Reset();
  return true;
}
#endif

static EGLint * getContextAttributesList()
{
  static EGLint contextAttrList[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
  return contextAttrList;
}

AndroidOGLContext::AndroidOGLContext(EGLDisplay display, EGLSurface surface, EGLConfig config,
                                     AndroidOGLContext * contextToShareWith)
  : m_nativeContext(EGL_NO_CONTEXT)
  , m_surface(surface)
  , m_display(display)
  , m_presentAvailable(true)
{
  ASSERT(m_surface != EGL_NO_SURFACE, ());
  ASSERT(m_display != EGL_NO_DISPLAY, ());

  EGLContext sharedContext = (contextToShareWith == NULL) ? EGL_NO_CONTEXT : contextToShareWith->m_nativeContext;
  m_nativeContext = eglCreateContext(m_display, config, sharedContext, getContextAttributesList());
  CHECK(m_nativeContext != EGL_NO_CONTEXT, ());
}

AndroidOGLContext::~AndroidOGLContext()
{
  // Native context must exist
  if (eglDestroyContext(m_display, m_nativeContext) == EGL_FALSE)
    CHECK_EGL_CALL();
}

void AndroidOGLContext::SetFramebuffer(ref_ptr<dp::BaseFramebuffer> framebuffer)
{
  if (framebuffer)
    framebuffer->Bind();
  else
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

void AndroidOGLContext::MakeCurrent()
{
  ASSERT(m_surface != EGL_NO_SURFACE, ());
  if (eglMakeCurrent(m_display, m_surface, m_surface, m_nativeContext) == EGL_FALSE)
    CHECK_EGL_CALL();
}

void AndroidOGLContext::DoneCurrent()
{
  ClearCurrent();
}

void AndroidOGLContext::ClearCurrent()
{
  if (eglMakeCurrent(m_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) == EGL_FALSE)
    CHECK_EGL_CALL();
}

void AndroidOGLContext::SetRenderingEnabled(bool enabled)
{
  if (enabled)
    MakeCurrent();
  else
    ClearCurrent();
}

void AndroidOGLContext::SetPresentAvailable(bool available)
{
  m_presentAvailable = available;
}

bool AndroidOGLContext::Validate()
{
  if (!m_presentAvailable)
    return false;
  return eglGetCurrentDisplay() != EGL_NO_DISPLAY && eglGetCurrentSurface(EGL_DRAW) != EGL_NO_SURFACE &&
         eglGetCurrentContext() != EGL_NO_CONTEXT;
}

void AndroidOGLContext::Present()
{
  if (!m_presentAvailable)
    return;
  ASSERT(m_surface != EGL_NO_SURFACE, ());
  if (eglSwapBuffers(m_display, m_surface) == EGL_FALSE)
    CHECK_EGL_CALL();
}

void AndroidOGLContext::SetSurface(EGLSurface surface)
{
  m_surface = surface;
  ASSERT(m_surface != EGL_NO_SURFACE, ());
}

void AndroidOGLContext::ResetSurface()
{
  m_surface = EGL_NO_SURFACE;
}
}  // namespace android
