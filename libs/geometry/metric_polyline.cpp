#include "geometry/metric_polyline.hpp"

#include "geometry/mercator.hpp"
#include "geometry/smoothing.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace m2
{
MetricPolyline::MetricPolyline(std::vector<PointD> const & path, bool smooth, double startOffsetMeters,
                               double endOffsetMeters)
{
  for (auto const & point : path)
    if (m_points.empty() || (point - m_points.back()).SquaredLength() > 1e-20)
      m_points.push_back(point);
  if (!IsValid())
    return;
  if (smooth && m_points.size() >= 4)
  {
    auto const origin = m_points.front();
    double const scale = mercator::DistanceOnEarth(origin, origin + PointD(0.00001, 0)) / 0.00001;
    std::vector<std::vector<PointD>> paths(1);
    for (auto const & point : m_points)
      paths[0].push_back((point - origin) * scale);
    auto const & local = paths[0];
    bool const closed = local.front() == local.back();
    PointD const before = closed ? local[local.size() - 2] : local.front() * 2 - local[1];
    PointD const after = closed ? local[1] : local.back() * 2 - local[local.size() - 2];
    SmoothPaths({{before, after}}, 4, kCentripetalAlpha, paths);
    m_points.clear();
    for (auto const & point : paths[0])
      m_points.push_back(origin + point / scale);
  }
  m_distances.resize(m_points.size(), 0);
  for (size_t i = 0; i < m_points.size(); ++i)
  {
    bool const closed = m_points.front() == m_points.back();
    auto const prev = i == 0 ? (closed ? m_points.size() - 2 : 0) : i - 1;
    auto const next = i + 1 == m_points.size() ? (closed ? 1 : i) : i + 1;
    auto before = m_points[i] - m_points[prev];
    auto after = m_points[next] - m_points[i];
    if (before.SquaredLength() == 0)
      before = after;
    if (after.SquaredLength() == 0)
      after = before;
    before = before.Normalize();
    after = after.Normalize();
    auto const sum = before + after;
    if (sum.Length() < 1.0)
    {
      m_points.clear();
      m_distances.clear();
      return;
    }
    auto const tangent = sum.Normalize();
    auto const meters = mercator::DistanceOnEarth(m_points[i], m_points[i] + PointD(0.00001, 0));
    m_leftPerMeter.emplace_back(-tangent.y, tangent.x);
    m_leftPerMeter.back() *= 0.00001 / meters / DotProduct(tangent, after);
    if (i != 0)
      m_distances[i] = m_distances[i - 1] + mercator::DistanceOnEarth(m_points[i - 1], m_points[i]);
  }
  if (startOffsetMeters != 0 || endOffsetMeters != 0)
  {
    auto shifted = m_points;
    for (size_t i = 0; i < shifted.size(); ++i)
      shifted[i] += m_leftPerMeter[i] * std::lerp(startOffsetMeters, endOffsetMeters, m_distances[i] / Length());
    *this = MetricPolyline(shifted);
  }
}

MetricPolyline::Frame MetricPolyline::Sample(double distance) const
{
  CHECK(IsValid(), ());
  distance = std::clamp(distance, 0.0, Length());
  auto const it = std::lower_bound(m_distances.begin() + 1, m_distances.end(), distance);
  size_t const i = std::min(static_cast<size_t>(it - m_distances.begin()), m_points.size() - 1);
  double const t = (distance - m_distances[i - 1]) / (m_distances[i] - m_distances[i - 1]);
  auto const normal = m_leftPerMeter[i - 1] + (m_leftPerMeter[i] - m_leftPerMeter[i - 1]) * t;
  return {m_points[i - 1] + (m_points[i] - m_points[i - 1]) * t, PointD(normal.y, -normal.x).Normalize(), normal};
}

void MetricPolyline::SetFrame(double distance, PointD const & position, PointD const & leftPerMeter)
{
  distance = std::clamp(distance, 0.0, Length());
  auto const it = std::lower_bound(m_distances.begin(), m_distances.end(), distance);
  size_t index = static_cast<size_t>(it - m_distances.begin());
  if (index > 0 && distance - m_distances[index - 1] < 1e-4)
    --index;
  if (index < m_distances.size() && std::abs(m_distances[index] - distance) < 1e-4)
  {
    m_points[index] = position;
    m_leftPerMeter[index] = leftPerMeter;
    return;
  }
  m_distances.insert(m_distances.begin() + index, distance);
  m_points.insert(m_points.begin() + index, position);
  m_leftPerMeter.insert(m_leftPerMeter.begin() + index, leftPerMeter);
}

double MetricPolyline::ProjectDistance(PointD const & point, double hint) const
{
  double bestSquared = std::numeric_limits<double>::max();
  double bestDistance = 0;
  for (size_t i = 1; i < m_points.size(); ++i)
  {
    auto const segment = m_points[i] - m_points[i - 1];
    double const t = std::clamp(DotProduct(point - m_points[i - 1], segment) / segment.SquaredLength(), 0.0, 1.0);
    double const squared = (m_points[i - 1] + segment * t - point).SquaredLength();
    double const distance = m_distances[i - 1] + t * (m_distances[i] - m_distances[i - 1]);
    if (squared < bestSquared ||
        (std::abs(squared - bestSquared) < 1e-20 && std::abs(distance - hint) < std::abs(bestDistance - hint)))
    {
      bestSquared = squared;
      bestDistance = distance;
    }
  }
  return bestDistance;
}
}  // namespace m2
