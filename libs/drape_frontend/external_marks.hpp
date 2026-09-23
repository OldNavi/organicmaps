#pragma once

#include "drape_frontend/user_mark_shapes.hpp"

#include <memory>

namespace df
{
// A bounded, viewport-based layer. Implementations must support concurrent renderer queries.
class ExternalMarks
{
public:
  struct RenderData
  {
    drape_ptr<UserMarksRenderCollection> m_marks = make_unique_dp<UserMarksRenderCollection>();
    drape_ptr<UserLinesRenderCollection> m_lines = make_unique_dp<UserLinesRenderCollection>();
  };
  virtual ~ExternalMarks() = default;
  virtual uint64_t Revision() const = 0;
  virtual RenderData Query(m2::RectD const & rect, int zoom) const = 0;
  virtual kml::MarkGroupId GroupId() const = 0;
};
}  // namespace df
