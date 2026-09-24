#pragma once

#include "drape/drape_global.hpp"
#include "drape/graphics_context.hpp"
#include "drape/pointers.hpp"
#include "drape/texture.hpp"
#ifdef OMIM_AUTO
#include "drape/gl_functions.hpp"
#endif

#include <cstdint>
#include <functional>

namespace dp
{
class FramebufferTexture : public Texture
{
public:
  ref_ptr<ResourceInfo> FindResource(Key const & key, bool & newResource) override { return nullptr; }
};

using FramebufferFallback = std::function<bool()>;

class Framebuffer : public BaseFramebuffer
{
public:
  class DepthStencil
  {
  public:
    DepthStencil(bool depthEnabled, bool stencilEnabled);
    ~DepthStencil();
    void SetSize(ref_ptr<dp::GraphicsContext> context, uint32_t width, uint32_t height);
    void Destroy();
    uint32_t GetDepthAttachmentId() const;
    uint32_t GetStencilAttachmentId() const;
    ref_ptr<FramebufferTexture> GetTexture() const;

  private:
    bool const m_depthEnabled = false;
    bool const m_stencilEnabled = false;
    drape_ptr<FramebufferTexture> m_texture;
  };

  explicit Framebuffer(TextureFormat colorFormat);
  Framebuffer(TextureFormat colorFormat, bool depthEnabled, bool stencilEnabled);
  ~Framebuffer() override;

  void SetFramebufferFallback(FramebufferFallback && fallback);
  void SetSize(ref_ptr<dp::GraphicsContext> context, uint32_t width, uint32_t height);
  void SetDepthStencilRef(ref_ptr<DepthStencil> depthStencilRef);
  void ApplyOwnDepthStencil();
#ifdef OMIM_AUTO
  void SetSamples(uint32_t samples);
  uint32_t GetSamples() const { return m_multisample.m_samples; }
  // Resolve color and leave the texture-backed framebuffer bound for subsequent use/readback.
  void Resolve();
#endif

  void Bind() override;
  void ApplyFallback();

  ref_ptr<Texture> GetTexture() const;
  ref_ptr<DepthStencil> GetDepthStencilRef() const;

  bool IsSupported() const { return m_isSupported; }

private:
  void Destroy();

  drape_ptr<DepthStencil> m_depthStencil;
  ref_ptr<DepthStencil> m_depthStencilRef;
  drape_ptr<FramebufferTexture> m_colorTexture;
  uint32_t m_width = 0;
  uint32_t m_height = 0;
  uint32_t m_framebufferId = 0;
#ifdef OMIM_AUTO
  uint32_t m_requestedSamples = 0;
  GLFunctions::MultisampleFramebuffer m_multisample;
#endif
  TextureFormat m_colorFormat;
  FramebufferFallback m_framebufferFallback;
  bool m_isSupported = true;
};
}  // namespace dp
