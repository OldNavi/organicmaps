#pragma once

#include "geometry/point2d.hpp"

#include <vector>

namespace m2
{
// A shared metric frame for generator junction cuts and renderer ribbons.
class MetricPolyline
{
public:
  struct Frame
  {
    PointD m_position;
    PointD m_tangent;
    PointD m_leftPerMeter;
  };

  explicit MetricPolyline(std::vector<PointD> const & path, bool smooth = false, double startOffsetMeters = 0,
                          double endOffsetMeters = 0);
  bool IsValid() const { return m_points.size() > 1; }
  double Length() const { return m_distances.empty() ? 0 : m_distances.back(); }
  Frame Sample(double distance) const;
  // Anchor a shared junction cross-section without restarting the distance/dash coordinate.
  void SetFrame(double distance, PointD const & position, PointD const & leftPerMeter);
  double ProjectDistance(PointD const & point, double hint) const;

  std::vector<PointD> const & Points() const { return m_points; }
  std::vector<PointD> const & Normals() const { return m_leftPerMeter; }
  std::vector<double> const & Distances() const { return m_distances; }

private:
  std::vector<PointD> m_points;
  std::vector<PointD> m_leftPerMeter;
  std::vector<double> m_distances;
};
}  // namespace m2
