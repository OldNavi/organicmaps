#pragma once

#include "indexer/road_details.hpp"

#include "geometry/metric_polyline.hpp"
#include "geometry/rect2d.hpp"

#include <limits>
#include <vector>

namespace df
{
// Built on tile workers from the complete, finest-resolution feature path.
// Offsets and dash phase are calculated before clipping, independently of the tile and camera.
class RoadDetailGeometry
{
public:
  explicit RoadDetailGeometry(std::vector<m2::PointD> const & path,
                              feature::RoadJunctions::Links const & junctions = {}, bool smooth = false,
                              double startOffsetMeters = 0, double endOffsetMeters = 0);
  bool IsValid() const { return m_path.IsValid(); }
  std::vector<feature::RoadJunction> const & BranchJunctions() const { return m_branchJunctions; }
  std::vector<m2::PointD> Surface(double widthMeters, m2::RectD const & clip, bool dashed = false) const;
  // Junction patches are drawn separately, but their reserved spans must still hide lower labels.
  std::vector<m2::PointD> LabelSurface(double widthMeters, m2::RectD const & clip) const;
  std::vector<m2::PointD> Arrows(feature::RoadDetails const & details, m2::RectD const & clip) const;
  std::vector<m2::PointD> Separator(m2::RectD const & clip) const;
  std::vector<m2::PointD> Markings(feature::RoadDetails const & details, m2::RectD const & clip) const;

  std::vector<m2::PointD> JunctionEdge(feature::RoadJunction const & junction, size_t armIndex, bool left,
                                       double casingMeters = 0) const;

  std::vector<m2::PointD> JunctionPatch(feature::RoadJunction const & junction, size_t armIndex, double reachMeters,
                                        bool markings, m2::RectD const & clip) const;

private:
  void Strip(double offsetMeters, double widthMeters, bool dashed, m2::RectD const & clip,
             std::vector<m2::PointD> & triangles, double from = 0, double to = std::numeric_limits<double>::max(),
             bool clipAtJunctions = true) const;
  m2::MetricPolyline m_path;
  std::vector<std::pair<double, double>> m_visible;
  // Keep clipping constraints independent of the lifetime of the MWM reader.
  std::vector<feature::RoadJunction> m_branchJunctions;
};
}  // namespace df
