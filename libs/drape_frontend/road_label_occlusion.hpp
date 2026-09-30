#pragma once

#include "drape_frontend/road_detail_geometry.hpp"
#include "geometry/spline.hpp"

#include <map>
#include <memory>

namespace df
{
// Tile-worker cache. Label paths are split before layout, so an overpass never cuts through a word.
class RoadLabelOcclusion
{
public:
  void Add(int layer, double widthMeters, std::shared_ptr<RoadDetailGeometry const> geometry);
  void AddJunction(int layer, feature::RoadJunction const & junction);
  std::vector<m2::SharedSpline> Clip(std::vector<m2::SharedSpline> const & splines, int layer, double paddingMeters,
                                     m2::RectD const & tile);

private:
  struct Road
  {
    int m_layer;
    double m_width;
    std::shared_ptr<RoadDetailGeometry const> m_geometry;
    std::map<double, std::vector<m2::PointD>> m_surfaces;
  };
  struct Junction
  {
    int m_layer;
    feature::RoadJunction m_geometry;
    std::map<double, std::vector<m2::PointD>> m_surfaces;
  };
  std::vector<Road> m_roads;
  std::vector<Junction> m_junctions;
};
}  // namespace df
