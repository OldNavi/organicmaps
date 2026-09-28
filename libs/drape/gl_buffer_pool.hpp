#pragma once

#ifdef OMIM_AUTO

#include "drape/gl_constants.hpp"

#include <atomic>
#include <map>
#include <mutex>
#include <thread>
#include <vector>

namespace dp
{
// Unused storage is bounded and belongs to one map's GL share group.
class GLBufferPool
{
public:
  struct Buffer
  {
    uint32_t m_id = 0;
    uint32_t m_bytes = 0;
    glConst m_target = 0;
  };

  static GLBufferPool & Instance();
  void Enable() { m_enabled = true; }
  bool IsEnabled() const { return m_enabled; }
  Buffer Acquire(glConst target, uint32_t bytes);
  bool Recycle(Buffer buffer);
  // Retire a batch behind one fence, without waiting for the GPU or holding the pool mutex in GL calls.
  void EndFrame();
  void Clear();

private:
  struct Batch
  {
    void * m_fence = nullptr;
    std::vector<Buffer> m_buffers;
  };
  static void Delete(std::vector<Buffer> const & buffers);
  static void Delete(std::vector<Batch> const & batches);
  static constexpr uint32_t kMaxBytes = 16 * 1024 * 1024;
  std::atomic<bool> m_enabled{false};
  std::mutex m_mutex;
  uint64_t m_generation = 0;
  uint32_t m_bytes = 0;
  std::multimap<std::pair<glConst, uint32_t>, Buffer> m_ready;
  std::map<std::thread::id, std::vector<Buffer>> m_pending;
  std::vector<Batch> m_retired;
};
}  // namespace dp
#endif
