#include "drape_frontend/road_label_occlusion.hpp"
#include "drape_frontend/road_junction_geometry.hpp"

#include <algorithm>
#include <array>
#include <cmath>

namespace df
{
void RoadLabelOcclusion::Add(int layer, double widthMeters, std::shared_ptr<RoadDetailGeometry const> geometry)
{
  if (widthMeters > 0 && geometry && geometry->IsValid())
    m_roads.push_back({layer, widthMeters, std::move(geometry), {}});
}

void RoadLabelOcclusion::AddJunction(int layer, feature::RoadJunction const & junction)
{
  if (!junction.HasContinuation())
    m_junctions.push_back({layer, junction, {}});
}

std::vector<m2::SharedSpline> RoadLabelOcclusion::Clip(std::vector<m2::SharedSpline> const & splines, int layer,
                                                       double paddingMeters, m2::RectD const & tile)
{
  struct Triangle
  {
    std::array<m2::PointD, 3> m_points;
    m2::RectD m_bounds;
  };
  std::vector<Triangle> triangles;
  auto const append = [&](std::vector<m2::PointD> const & surface)
  {
    for (size_t i = 0; i < surface.size(); i += 3)
    {
      if (std::abs(CrossProduct(surface[i + 1] - surface[i], surface[i + 2] - surface[i])) < 1e-20)
        continue;
      Triangle triangle{{surface[i], surface[i + 1], surface[i + 2]}, {}};
      for (auto const & point : triangle.m_points)
        triangle.m_bounds.Add(point);
      triangles.push_back(triangle);
    }
  };
  for (auto & road : m_roads)
  {
    if (road.m_layer <= layer)
      continue;
    auto [it, inserted] = road.m_surfaces.try_emplace(paddingMeters);
    if (inserted)
      it->second = road.m_geometry->LabelSurface(road.m_width + 2 * paddingMeters, tile);
    append(it->second);
  }
  for (auto & junction : m_junctions)
  {
    if (junction.m_layer <= layer)
      continue;
    auto [it, inserted] = junction.m_surfaces.try_emplace(paddingMeters);
    if (inserted)
      it->second = BuildRoadJunctionSurface(junction.m_geometry, tile, 2 * paddingMeters);
    append(it->second);
  }
  if (triangles.empty())
    return splines;

  std::vector<m2::SharedSpline> result;
  for (auto const & spline : splines)
  {
    std::vector<m2::PointD> visible;
    auto const flush = [&]
    {
      if (visible.size() > 1)
        result.emplace_back(std::move(visible));
      visible.clear();
    };
    auto const & path = spline->GetPath();
    for (size_t i = 1; i < path.size(); ++i)
    {
      auto const a = path[i - 1], delta = path[i] - a;
      m2::RectD segmentBounds;
      segmentBounds.Add(a);
      segmentBounds.Add(path[i]);
      std::vector<std::pair<double, double>> blocked;
      for (auto const & triangle : triangles)
      {
        if (!triangle.m_bounds.IsIntersect(segmentBounds))
          continue;
        double enter = 0, leave = 1;
        for (size_t k = 0; k < 3 && enter < leave; ++k)
        {
          auto const edge = triangle.m_points[(k + 1) % 3] - triangle.m_points[k];
          double const side = CrossProduct(edge, a - triangle.m_points[k]);
          double const slope = CrossProduct(edge, delta);
          if (std::abs(slope) < 1e-20)
          {
            if (side > 1e-20)
              leave = -1;
          }
          else if (slope > 0)
            leave = std::min(leave, -side / slope);
          else
            enter = std::max(enter, -side / slope);
        }
        if (enter < leave)
          blocked.emplace_back(enter, leave);
      }
      std::sort(blocked.begin(), blocked.end());
      auto const append = [&](double begin, double end)
      {
        if (end - begin < 1e-10)
          return;
        if (visible.empty())
          visible.push_back(a + delta * begin);
        visible.push_back(a + delta * end);
      };
      double begin = 0;
      for (auto const & interval : blocked)
      {
        if (interval.first > begin)
          append(begin, interval.first);
        flush();
        begin = std::max(begin, interval.second);
      }
      if (begin < 1)
        append(begin, 1);
    }
    flush();
  }
  return result;
}
}  // namespace df
