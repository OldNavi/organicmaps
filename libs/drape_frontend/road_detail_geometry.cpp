#include "drape_frontend/road_detail_geometry.hpp"

#include "geometry/clipping.hpp"
#include "geometry/mercator.hpp"

#include <algorithm>
#include <cmath>

namespace df
{
RoadDetailGeometry::RoadDetailGeometry(std::vector<m2::PointD> const & path,
                                       feature::RoadJunctions::Links const & junctions, bool smooth,
                                       double startOffsetMeters, double endOffsetMeters)
  : m_path(path, smooth, startOffsetMeters, endOffsetMeters)
{
  if (!IsValid())
    return;
  std::vector<std::pair<double, double>> cuts;
  for (auto const & link : junctions)
  {
    auto const & arm = link.Arm();
    auto const & junction = *link.m_junction;
    if (junction.HasContinuation())
    {
      if (arm.m_featureEndpoint &&
          (link.m_armIndex == junction.m_continuationA || link.m_armIndex == junction.m_continuationB))
      {
        auto const & a = junction.m_arms[junction.m_continuationA];
        auto const & b = junction.m_arms[junction.m_continuationB];
        auto const tangent = (a.m_nodeDirectionAway - b.m_nodeDirectionAway).Normalize();
        double const units =
            0.00001 / mercator::DistanceOnEarth(junction.m_center, junction.m_center + m2::PointD(0.00001, 0));
        auto normal = m2::PointD(-tangent.y, tangent.x) * (units / DotProduct(tangent, a.m_nodeDirectionAway));
        normal *= (link.m_armIndex == junction.m_continuationA ? 1 : -1) * (arm.m_forward ? 1 : -1);
        m_path.SetFrame(arm.m_forward ? 0 : m_path.Length(),
                        junction.m_center + normal * (arm.m_forward ? startOffsetMeters : endOffsetMeters), normal);
      }
      continue;
    }
    double const center = arm.m_featureEndpoint ? (arm.m_forward ? 0 : m_path.Length())
                                                : m_path.ProjectDistance(link.m_junction->m_center, arm.m_nodeDistance);
    double const cut =
        m_path.ProjectDistance(arm.m_position, arm.m_nodeDistance + (arm.m_forward ? 1 : -1) * arm.m_cutDistance);
    // MWM simplification can remove vertices used to construct the junction. Both sides must use
    // its exact cut plane, not a newly interpolated normal from the simplified feature.
    m_path.SetFrame(cut, arm.m_position, arm.m_normalAwayPerMeter * (arm.m_forward ? 1 : -1));
    cuts.emplace_back(std::min(center, cut), std::max(center, cut));
  }
  std::sort(cuts.begin(), cuts.end());
  double begin = 0;
  for (auto const & cut : cuts)
  {
    if (cut.first > begin)
      m_visible.emplace_back(begin, cut.first);
    begin = std::max(begin, cut.second);
  }
  if (begin < m_path.Length())
    m_visible.emplace_back(begin, m_path.Length());
}

std::vector<m2::PointD> RoadDetailGeometry::JunctionEdge(feature::RoadJunction const & junction, size_t armIndex,
                                                         bool left, double casingMeters) const
{
  auto const & arm = junction.m_arms[armIndex];
  double const center = m_path.ProjectDistance(junction.m_center, arm.m_nodeDistance);
  double const end = std::clamp(center + (arm.m_forward ? 1 : -1) * arm.m_cutDistance, 0.0, m_path.Length());
  std::vector<double> distances{center, end};
  for (auto const distance : m_path.Distances())
    if (distance > std::min(center, end) && distance < std::max(center, end))
      distances.push_back(distance);
  std::sort(distances.begin(), distances.end());
  if (!arm.m_forward)
    std::reverse(distances.begin(), distances.end());
  double const offset = (arm.WidthMeters() + casingMeters) / 2 * (left ? 1 : -1) * (arm.m_forward ? 1 : -1);
  std::vector<m2::PointD> edge;
  for (auto const distance : distances)
  {
    auto const frame = m_path.Sample(distance);
    edge.push_back(frame.m_position + frame.m_leftPerMeter * offset);
  }
  return edge;
}

std::vector<m2::PointD> RoadDetailGeometry::JunctionPatch(feature::RoadJunction const & junction, size_t armIndex,
                                                          double reachMeters, bool markings,
                                                          m2::RectD const & clip) const
{
  auto const & arm = junction.m_arms[armIndex];
  double const center = m_path.ProjectDistance(junction.m_center, arm.m_nodeDistance);
  double const end = std::clamp(center + (arm.m_forward ? 1 : -1) * reachMeters, 0.0, m_path.Length());
  double const from = std::min(center, end), to = std::max(center, end);
  std::vector<m2::PointD> triangles;
  if (!markings)
    Strip(0, arm.WidthMeters(), false, clip, triangles, from, to);
  else if (arm.m_markings)
  {
    double offset = arm.WidthMeters() / 2;
    for (size_t i = 0; i + 1 < arm.m_widthsCm.size(); ++i)
    {
      offset -= arm.m_widthsCm[i] * 0.01;
      Strip(offset, 0.15, true, clip, triangles, from, to);
    }
  }
  return triangles;
}

void RoadDetailGeometry::Strip(double offsetMeters, double widthMeters, bool dashed, m2::RectD const & clip,
                               std::vector<m2::PointD> & triangles, double from, double to) const
{
  auto const addTriangle = [&](m2::PointD const & a, m2::PointD const & b, m2::PointD const & c)
  {
    auto const emit = [&](auto const & p, auto const & q, auto const & r)
    { triangles.insert(triangles.end(), {p, q, r}); };
    if (CrossProduct(b - a, c - a) < 0)
      m2::ClipTriangleByRect(clip, a, b, c, emit);
    else
      m2::ClipTriangleByRect(clip, a, c, b, emit);
  };
  for (size_t i = 1; i < m_path.Points().size(); ++i)
  {
    auto const a = m_path.Points()[i - 1] + m_path.Normals()[i - 1] * (offsetMeters + widthMeters / 2);
    auto const b = m_path.Points()[i - 1] + m_path.Normals()[i - 1] * (offsetMeters - widthMeters / 2);
    auto const c = m_path.Points()[i] + m_path.Normals()[i] * (offsetMeters + widthMeters / 2);
    auto const d = m_path.Points()[i] + m_path.Normals()[i] * (offsetMeters - widthMeters / 2);
    m2::RectD bounds;
    for (auto const & p : {a, b, c, d})
      bounds.Add(p);
    if (!bounds.IsIntersect(clip))
      continue;
    auto const quad = [&](double begin, double end)
    {
      auto const p = a + (c - a) * begin;
      auto const q = b + (d - b) * begin;
      auto const r = a + (c - a) * end;
      auto const s = b + (d - b) * end;
      addTriangle(p, q, r);
      addTriangle(q, s, r);
    };
    double constexpr kPeriodMeters = 12.0;
    double constexpr kDashMeters = 3.0;
    double const start = m_path.Distances()[i - 1];
    double const finish = m_path.Distances()[i];
    for (auto const & range : m_visible)
    {
      double const begin = std::max({start, range.first, from});
      double const end = std::min({finish, range.second, to});
      if (begin >= end)
        continue;
      if (!dashed)
      {
        quad((begin - start) / (finish - start), (end - start) / (finish - start));
        continue;
      }
      for (double dash = std::floor(begin / kPeriodMeters) * kPeriodMeters; dash < end; dash += kPeriodMeters)
      {
        auto const dashBegin = std::max(begin, dash);
        auto const dashEnd = std::min(end, dash + kDashMeters);
        if (dashBegin < dashEnd)
          quad((dashBegin - start) / (finish - start), (dashEnd - start) / (finish - start));
      }
    }
  }
}

std::vector<m2::PointD> RoadDetailGeometry::Surface(double widthMeters, m2::RectD const & clip, bool dashed) const
{
  std::vector<m2::PointD> triangles;
  Strip(0, widthMeters, dashed, clip, triangles);
  return triangles;
}

std::vector<m2::PointD> RoadDetailGeometry::Separator(m2::RectD const & clip) const
{
  std::vector<m2::PointD> triangles;
  Strip(0, 0.15, true, clip, triangles);
  return triangles;
}

std::vector<m2::PointD> RoadDetailGeometry::Markings(feature::RoadDetails const & details, m2::RectD const & clip) const
{
  std::vector<m2::PointD> triangles;
  if (!details.m_markings)
    return triangles;
  double offset = details.WidthMeters() / 2;
  // These are schematic lane divisions, not inferred overtaking restrictions or surveyed road paint.
  for (size_t i = 0; i + 1 < details.m_lanes.size(); ++i)
  {
    offset -= details.m_lanes[i].m_widthCm * 0.01;
    Strip(offset, 0.15, true, clip, triangles);
  }
  return triangles;
}
std::vector<m2::PointD> RoadDetailGeometry::Arrows(feature::RoadDetails const & details, m2::RectD const & clip) const
{
  std::vector<m2::PointD> triangles;
  if (!IsValid() || (!details.m_oneWay && (!details.m_markings || details.m_backwardLanes == 0)))
    return triangles;
  auto const append = [&](m2::PointD a, m2::PointD b, m2::PointD c)
  {
    if (CrossProduct(b - a, c - a) > 0)
      std::swap(b, c);
    m2::ClipTriangleByRect(clip, a, b, c, [&](auto const & p, auto const & q, auto const & r)
    { triangles.insert(triangles.end(), {p, q, r}); });
  };
  size_t const count = details.m_markings ? details.m_lanes.size() : 1;
  double const step = details.m_roundabout ? std::min(80.0, m_path.Length() / 4) : 80.0;
  for (double distance = std::min(20.0, step / 2); distance + 2 < m_path.Length(); distance += step)
  {
    if (!std::any_of(m_visible.begin(), m_visible.end(),
                     [&](auto const & range) { return range.first <= distance - 2 && range.second >= distance + 2; }))
      continue;
    auto const frame = m_path.Sample(distance);
    double offset = details.WidthMeters() / 2;
    for (size_t lane = 0; lane < count; ++lane)
    {
      double const width = details.m_markings ? details.m_lanes[lane].m_widthCm * 0.01 : details.WidthMeters();
      offset -= width / 2;
      auto const origin = frame.m_position + frame.m_leftPerMeter * offset;
      offset -= width / 2;
      bool backward = false;
      if (!details.m_oneWay)
      {
        size_t const forward = details.m_lanes.size() - details.m_backwardLanes - details.m_sharedLanes;
        size_t const sharedBegin = details.m_leftHand ? forward : details.m_backwardLanes;
        if (lane >= sharedBegin && lane < sharedBegin + details.m_sharedLanes)
          continue;
        backward = details.m_leftHand ? lane >= forward + details.m_sharedLanes : lane < details.m_backwardLanes;
      }
      double const units = 0.00001 / mercator::DistanceOnEarth(origin, origin + m2::PointD(0.00001, 0));
      auto const ahead = frame.m_tangent * ((backward ? -1 : 1) * units);
      m2::PointD const left(-ahead.y, ahead.x);
      double const glyphScale = std::min(1.0, width * 0.4 / 1.3);
      auto const transform = [&](m2::PointD const & p) { return origin + (left * p.x + ahead * p.y) * glyphScale; };
      auto const stroke = [&](std::vector<m2::PointD> const & points)
      {
        for (size_t i = 1; i < points.size(); ++i)
        {
          auto const tangent = (points[i] - points[i - 1]).Normalize();
          m2::PointD const normal(-tangent.y * 0.11, tangent.x * 0.11);
          auto const a = transform(points[i - 1] + normal), b = transform(points[i - 1] - normal);
          auto const c = transform(points[i] + normal), d = transform(points[i] - normal);
          append(a, b, c);
          append(b, d, c);
        }
      };
      auto const arrow = [&](std::vector<m2::PointD> const & points)
      {
        stroke(points);
        auto const tip = points.back();
        auto const tangent = (tip - points[points.size() - 2]).Normalize();
        m2::PointD const normal(-tangent.y * 0.48, tangent.x * 0.48);
        append(transform(tip), transform(tip - tangent * 0.75 + normal), transform(tip - tangent * 0.75 - normal));
      };
      using D = feature::RoadDetails;
      uint16_t const turns = details.m_markings ? details.m_lanes[lane].m_turns : 0;
      if (details.m_markings && details.m_lanes[lane].m_publicTransport)
      {
        double const shift = turns == 0 ? 0 : -6;
        if (std::any_of(m_visible.begin(), m_visible.end(), [&](auto const & range)
        { return range.first <= distance + shift - 2 && range.second >= distance + shift + 2; }))
        {
          stroke({{-0.6, shift - 1.0},
                  {-0.6, shift + 1.0},
                  {-0.4, shift + 1.25},
                  {0.4, shift + 1.25},
                  {0.6, shift + 1.0},
                  {0.6, shift - 1.0},
                  {-0.6, shift - 1.0}});
          stroke(
              {{-0.4, shift + 0.1}, {-0.4, shift + 0.8}, {0.4, shift + 0.8}, {0.4, shift + 0.1}, {-0.4, shift + 0.1}});
          stroke({{-0.4, shift - 0.55}, {-0.2, shift - 0.55}});
          stroke({{0.2, shift - 0.55}, {0.4, shift - 0.55}});
          stroke({{-0.4, shift - 1.0}, {-0.4, shift - 1.35}});
          stroke({{0.4, shift - 1.0}, {0.4, shift - 1.35}});
        }
        if (turns == 0)
          continue;
      }
      if (turns == 0 || (turns & D::Through))
        arrow({{0, -1.4}, {0, 1.4}});
      if (turns & (D::Left | D::SharpLeft))
        arrow({{0, -1.4}, {0, -0.2}, {0.25, 0.2}, {1.3, 0.2}});
      if (turns & (D::Right | D::SharpRight))
        arrow({{0, -1.4}, {0, -0.2}, {-0.25, 0.2}, {-1.3, 0.2}});
      if (turns & (D::SlightLeft | D::MergeLeft))
        arrow({{0, -1.4}, {0, -0.3}, {1.0, 1.2}});
      if (turns & (D::SlightRight | D::MergeRight))
        arrow({{0, -1.4}, {0, -0.3}, {-1.0, 1.2}});
      if (turns & D::Reverse)
        arrow({{0, -1.4}, {0, 0.3}, {0.45, 0.7}, {0.9, 0.3}, {0.9, -0.8}});
    }
  }
  return triangles;
}
}  // namespace df
