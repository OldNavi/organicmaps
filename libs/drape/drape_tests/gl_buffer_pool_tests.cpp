#include "testing/testing.hpp"

#ifdef OMIM_AUTO
#include "drape/drape_tests/gl_mock_functions.hpp"
#include "drape/gl_buffer_pool.hpp"
#include "drape/gpu_buffer.hpp"
#include "drape/render_context.hpp"

using ::testing::Return;

UNIT_TEST(GLBufferPool_ReusesOnlyCompletedStorage)
{
  dp::GLBufferPool pool;
  pool.Enable();
  TEST(pool.Recycle({7, 4096, gl_const::GLArrayBuffer}), ());
  TEST_EQUAL(pool.Acquire(gl_const::GLArrayBuffer, 4096).m_id, 0, ("Not fenced yet"));
  int fence;
  EXPECTGL(CreateFence()).WillOnce(Return(&fence));
  pool.EndFrame();
  EXPECTGL(IsFenceSignaled(&fence)).WillOnce(Return(false));
  pool.EndFrame();
  TEST_EQUAL(pool.Acquire(gl_const::GLArrayBuffer, 4096).m_id, 0, ("GPU still uses this storage"));
  EXPECTGL(IsFenceSignaled(&fence)).WillOnce(Return(true));
  EXPECTGL(DeleteFence(&fence));
  pool.EndFrame();
  TEST_EQUAL(pool.Acquire(gl_const::GLElementArrayBuffer, 4096).m_id, 0, ("Different target"));
  TEST_EQUAL(pool.Acquire(gl_const::GLArrayBuffer, 4097).m_id, 0, ("Insufficient capacity"));
  auto const reused = pool.Acquire(gl_const::GLArrayBuffer, 3000);
  TEST_EQUAL(reused.m_id, 7, ());
  TEST_EQUAL(reused.m_bytes, 4096, ());
  TEST(pool.Recycle(reused), ());
  EXPECTGL(glDeleteBuffer(7));
  pool.Clear();
  TEST(!pool.Recycle({8, 64, gl_const::GLArrayBuffer}), ("Context teardown disables reuse"));
}

UNIT_TEST(GLBufferPool_BoundsPendingMemoryAndDeletesFences)
{
  dp::GLBufferPool pool;
  pool.Enable();
  TEST(pool.Recycle({1, 8 * 1024 * 1024, gl_const::GLArrayBuffer}), ());
  TEST(pool.Recycle({2, 8 * 1024 * 1024, gl_const::GLArrayBuffer}), ());
  TEST(!pool.Recycle({3, 1, gl_const::GLArrayBuffer}), ("Pending GPU work counts towards the limit"));
  int fence;
  EXPECTGL(CreateFence()).WillOnce(Return(&fence));
  pool.EndFrame();
  EXPECTGL(DeleteFence(&fence));
  EXPECTGL(glDeleteBuffer(1));
  EXPECTGL(glDeleteBuffer(2));
  pool.Clear();
  pool.Enable();
  TEST_EQUAL(pool.Acquire(gl_const::GLArrayBuffer, 1024).m_id, 0, ("Old context IDs cannot survive recreation"));
  pool.Clear();
}

UNIT_TEST(GPUBuffer_ReusesStorageWithoutBufferData)
{
  dp::RenderContext::Scope scope(std::make_shared<dp::RenderContext>());
  auto & pool = dp::GLBufferPool::Instance();
  pool.Enable();
  TEST(pool.Recycle({42, 4096, gl_const::GLArrayBuffer}), ());
  int fence;
  EXPECTGL(CreateFence()).WillOnce(Return(&fence));
  pool.EndFrame();
  EXPECTGL(IsFenceSignaled(&fence)).WillOnce(Return(true));
  EXPECTGL(DeleteFence(&fence));
  pool.EndFrame();
  uint32_t data[512]{};
  EXPECTGL(glBindBuffer(42, gl_const::GLArrayBuffer));
  EXPECTGL(glBufferSubData(gl_const::GLArrayBuffer, sizeof(data), data, 0));
  EXPECTGL(glBindBuffer(0, gl_const::GLArrayBuffer));
  {
    dp::GPUBuffer buffer(dp::GPUBuffer::ElementBuffer, data, sizeof(uint32_t), 512, 0);
    TEST_EQUAL(buffer.GetCurrentSize(), 512, ());
    TEST_EQUAL(buffer.GetCapacity(), 512, ("Physical storage capacity must not change the logical vertex range"));
  }
  EXPECTGL(glDeleteBuffer(42));
  pool.Clear();
}
#endif
