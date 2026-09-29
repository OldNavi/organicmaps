#include "drape_frontend/engine_context.hpp"

#include "drape/texture_manager.hpp"
#include "drape_frontend/message_subclasses.hpp"

#include <algorithm>
#include <iterator>
#include <utility>

namespace df
{
EngineContext::EngineContext(TileKey tileKey, ref_ptr<ThreadsCommutator> commutator, ref_ptr<dp::TextureManager> texMng,
                             ref_ptr<MetalineManager> metalineMng, CustomFeaturesContextWeakPtr customFeaturesContext,
                             bool is3dBuildingsEnabled, bool isTrafficEnabled, bool isolinesEnabled,
                             int8_t mapLangIndex, dp::BackgroundMode backgroundMode, float areaOpacity)
  : m_tileKey(tileKey)
  , m_commutator(commutator)
  , m_texMng(texMng)
  , m_metalineMng(metalineMng)
  , m_customFeaturesContext(customFeaturesContext)
  , m_3dBuildingsEnabled(is3dBuildingsEnabled)
  , m_trafficEnabled(isTrafficEnabled)
  , m_isolinesEnabled(isolinesEnabled)
  , m_mapLangIndex(mapLangIndex)
  , m_backgroundMode(backgroundMode)
  , m_areaOpacity(areaOpacity)
{}

ref_ptr<dp::TextureManager> EngineContext::GetTextureManager() const
{
  return m_texMng;
}

ref_ptr<MetalineManager> EngineContext::GetMetalineManager() const
{
  return m_metalineMng;
}

void EngineContext::BeginReadTile()
{
  ASSERT(m_geometry.empty() && m_overlays.empty(), ());
}

void EngineContext::Flush(TMapShapes && shapes)
{
  std::move(shapes.begin(), shapes.end(), std::back_inserter(m_geometry));
}

void EngineContext::FlushOverlays(TMapShapes && shapes)
{
  std::move(shapes.begin(), shapes.end(), std::back_inserter(m_overlays));
}

void EngineContext::FlushTrafficGeometry(TrafficSegmentsGeometry && geometry)
{
  m_commutator->PostMessage(ThreadsCommutator::ResourceUploadThread,
                            make_unique_dp<FlushTrafficGeometryMessage>(m_tileKey, std::move(geometry)),
                            MessagePriority::Low);
}

void EngineContext::EndReadTile(bool cancelled)
{
  if (!cancelled)
    PostMessage(make_unique_dp<TileReadBatchMessage>(m_tileKey, std::move(m_geometry), std::move(m_overlays)));
  m_geometry.clear();
  m_overlays.clear();
}

void EngineContext::PostMessage(drape_ptr<Message> && message)
{
  m_commutator->PostMessage(ThreadsCommutator::ResourceUploadThread, std::move(message), MessagePriority::Normal);
}
}  // namespace df
