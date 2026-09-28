#include "drape/gl_buffer_pool.hpp"

#ifdef OMIM_AUTO
#include "drape/gl_functions.hpp"
#include "drape/render_context.hpp"

#include <iterator>
#include <utility>

namespace dp
{
GLBufferPool & GLBufferPool::Instance()
{
  return RenderContext::Get<GLBufferPool>();
}

GLBufferPool::Buffer GLBufferPool::Acquire(glConst target, uint32_t bytes)
{
  std::lock_guard lock(m_mutex);
  if (!m_enabled || bytes == 0)
    return {};
  auto const it = m_ready.lower_bound({target, bytes});
  if (it == m_ready.end() || it->first.first != target || it->second.m_bytes / 2 > bytes)
    return {};
  auto const buffer = it->second;
  m_bytes -= buffer.m_bytes;
  m_ready.erase(it);
  return buffer;
}

bool GLBufferPool::Recycle(Buffer buffer)
{
  std::lock_guard lock(m_mutex);
  if (!m_enabled || buffer.m_bytes == 0 || buffer.m_bytes > kMaxBytes - m_bytes)
    return false;
  m_pending[std::this_thread::get_id()].push_back(buffer);
  m_bytes += buffer.m_bytes;
  return true;
}

void GLBufferPool::EndFrame()
{
  std::vector<Buffer> pending;
  std::vector<Batch> retired;
  uint64_t generation;
  {
    std::lock_guard lock(m_mutex);
    if (!m_enabled)
      return;
    generation = m_generation;
    auto const it = m_pending.find(std::this_thread::get_id());
    if (it != m_pending.end())
    {
      pending = std::move(it->second);
      m_pending.erase(it);
    }
    retired.swap(m_retired);
  }

  std::vector<Buffer> ready;
  std::vector<Batch> waiting;
  for (auto & batch : retired)
    if (GLFunctions::IsFenceSignaled(batch.m_fence))
    {
      GLFunctions::DeleteFence(batch.m_fence);
      std::move(batch.m_buffers.begin(), batch.m_buffers.end(), std::back_inserter(ready));
    }
    else
      waiting.push_back(std::move(batch));
  if (!pending.empty())
  {
    auto const fence = GLFunctions::CreateFence();
    GLFunctions::glFlush();  // Publish the fence to the other context in this map's share group.
    waiting.push_back({fence, std::move(pending)});
  }

  {
    std::lock_guard lock(m_mutex);
    if (m_enabled && generation == m_generation)
    {
      for (auto const & buffer : ready)
        m_ready.emplace(std::make_pair(buffer.m_target, buffer.m_bytes), buffer);
      std::move(waiting.begin(), waiting.end(), std::back_inserter(m_retired));
      return;
    }
  }
  Delete(ready);
  Delete(waiting);
}

void GLBufferPool::Delete(std::vector<Buffer> const & buffers)
{
  for (auto const & buffer : buffers)
    GLFunctions::glDeleteBuffer(buffer.m_id);
}

void GLBufferPool::Delete(std::vector<Batch> const & batches)
{
  for (auto const & batch : batches)
  {
    GLFunctions::DeleteFence(batch.m_fence);
    Delete(batch.m_buffers);
  }
}

void GLBufferPool::Clear()
{
  std::vector<Buffer> buffers;
  std::vector<Batch> retired;
  {
    std::lock_guard lock(m_mutex);
    m_enabled = false;
    ++m_generation;
    for (auto const & [key, buffer] : m_ready)
      buffers.push_back(buffer);
    for (auto & [thread, pending] : m_pending)
      std::move(pending.begin(), pending.end(), std::back_inserter(buffers));
    m_pending.clear();
    m_ready.clear();
    retired.swap(m_retired);
    m_bytes = 0;
  }
  Delete(buffers);
  Delete(retired);
}
}  // namespace dp
#endif
