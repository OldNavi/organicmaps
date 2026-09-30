#include "drape_frontend/road_junction_geometry.hpp"

#include "drape_frontend/road_detail_geometry.hpp"

#include "geometry/clipping.hpp"
#include "geometry/mercator.hpp"
#include "geometry/triangle2d.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <numeric>
#include <set>

#include "3party/libtess2/Include/tesselator.h"

namespace df
{
namespace
{
using Arm = feature::RoadJunctionArm;

std::vector<m2::PointD> Curve(m2::PointD const & a, m2::PointD const & aTangent, m2::PointD const & b,
                              m2::PointD const & bTangent, double firstControlFactor = 1, double lastControlFactor = 1)
{
  double const control = (b - a).Length() * 0.4;
  auto const c = a + aTangent * (control * firstControlFactor);
  auto const d = b - bTangent * (control * lastControlFactor);
  std::vector<m2::PointD> path;
  for (int i = 0; i <= 20; ++i)
  {
    double const t = i / 20.0, u = 1 - t;
    path.push_back(a * (u * u * u) + c * (3 * u * u * t) + d * (3 * u * t * t) + b * (t * t * t));
  }
  return path;
}

void AddRibbon(std::vector<m2::PointD> const & points, m2::PointD const & startNormal, m2::PointD const & endNormal,
               double startWidth, double endWidth, double casing, m2::RectD const & clip,
               std::vector<m2::PointD> & triangles)
{
  m2::MetricPolyline path(points);
  if (!path.IsValid())
    return;
  path.SetFrame(0, points.front(), startNormal);
  path.SetFrame(path.Length(), points.back(), endNormal);
  auto const emit = [&](m2::PointD a, m2::PointD b, m2::PointD c)
  {
    if (CrossProduct(b - a, c - a) > 0)
      std::swap(b, c);
    m2::ClipTriangleByRect(clip, a, b, c, [&](auto const & p, auto const & q, auto const & r)
    { triangles.insert(triangles.end(), {p, q, r}); });
  };
  for (size_t i = 1; i < path.Points().size(); ++i)
  {
    auto const edge = [&](size_t index, double side)
    {
      double const width = std::lerp(startWidth, endWidth, path.Distances()[index] / path.Length());
      return path.Points()[index] + path.Normals()[index] * ((width + casing) * side / 2);
    };
    auto const a = edge(i - 1, 1), b = edge(i - 1, -1), c = edge(i, 1), d = edge(i, -1);
    emit(a, b, c);
    emit(b, d, c);
  }
}

bool AddBalancedMergeLanes(feature::RoadJunction const & junction, double casing, m2::RectD const & clip,
                           std::vector<RoadLaneConnection> const & connections, std::vector<m2::PointD> & triangles)
{
  if (!connections.empty())
  {
    for (auto const & connection : connections)
    {
      auto const & in = junction.m_arms[connection.m_inArm];
      auto const & out = junction.m_arms[connection.m_outArm];
      auto const edge = [&](size_t index, bool left)
      {
        double const t = static_cast<double>(index) / (connection.m_path.size() - 1);
        double const width =
            std::lerp(in.m_widthsCm[connection.m_inLane] * 0.01, out.m_widthsCm[connection.m_outLane] * 0.01, t);
        auto const extra = (connection.m_leftEdge[index] - connection.m_rightEdge[index]) * (casing / (2 * width));
        return left ? connection.m_leftEdge[index] + extra : connection.m_rightEdge[index] - extra;
      };
      auto const emit = [&](m2::PointD a, m2::PointD b, m2::PointD c)
      {
        if (CrossProduct(b - a, c - a) > 0)
          std::swap(b, c);
        m2::ClipTriangleByRect(clip, a, b, c, [&](auto const & p, auto const & q, auto const & r)
        { triangles.insert(triangles.end(), {p, q, r}); });
      };
      for (size_t i = 1; i < connection.m_path.size(); ++i)
      {
        auto const a = edge(i - 1, true), b = edge(i - 1, false), c = edge(i, true), d = edge(i, false);
        emit(a, b, c);
        emit(b, d, c);
      }
    }
    return true;
  }
  if (junction.m_arms.size() != 3)
    return false;
  auto const wideIt =
      std::max_element(junction.m_arms.begin(), junction.m_arms.end(),
                       [](auto const & a, auto const & b) { return a.m_widthsCm.size() < b.m_widthsCm.size(); });
  auto const & wide = *wideIt;
  size_t count = 0;
  for (auto const & arm : junction.m_arms)
  {
    if (&arm == &wide)
      continue;
    if (DotProduct(arm.m_directionAway, wide.m_directionAway) > 0.5)
      return false;
    count += arm.m_widthsCm.size();
  }
  if (count < 2 || count != wide.m_widthsCm.size())
    return false;
  struct Port
  {
    Arm const * arm;
    m2::PointD point;
    double width;
  };
  std::vector<Port> ports;
  for (auto const & arm : junction.m_arms)
  {
    if (&arm == &wide)
      continue;
    double offset = arm.WidthMeters() / 2;
    for (size_t i = 0; i < arm.m_widthsCm.size(); ++i)
    {
      size_t const index = arm.m_forward ? i : arm.m_widthsCm.size() - 1 - i;
      double const width = arm.m_widthsCm[index] * 0.01;
      ports.push_back({&arm, arm.m_position + arm.m_normalAwayPerMeter * (offset - width / 2), width});
      offset -= width;
    }
  }
  auto const left = wide.m_normalAwayPerMeter.Normalize();
  std::sort(ports.begin(), ports.end(),
            [&](auto const & a, auto const & b) { return DotProduct(a.point - b.point, left) > 0; });
  std::vector<double> widths;
  std::vector<m2::PointD> targets, controls;
  double offset = wide.WidthMeters() / 2;
  for (size_t i = 0; i < count; ++i)
  {
    size_t const index = wide.m_forward ? i : count - 1 - i;
    double const width = wide.m_widthsCm[index] * 0.01;
    widths.push_back(width);
    targets.push_back(wide.m_position + wide.m_normalAwayPerMeter * (offset - width / 2));
    controls.push_back(ports[i].point -
                       ports[i].arm->m_directionAway * ((targets.back() - ports[i].point).Length() * 0.4));
    offset -= width;
  }
  double factor = 1;
  for (size_t i = 1; i < count; ++i)
  {
    if (ports[i - 1].arm == ports[i].arm)
      continue;
    double const gap = DotProduct(ports[i - 1].point - ports[i].point, left);
    double const controlGap = DotProduct(controls[i - 1] - controls[i], left);
    double const laneGap = wide.m_normalAwayPerMeter.Length() * (widths[i - 1] + widths[i]) / 2;
    if (controlGap < laneGap && controlGap < gap)
      factor = std::min(factor, std::clamp((gap - laneGap) / (gap - controlGap), 0.0, 1.0));
  }
  for (size_t i = 0; i < count; ++i)
  {
    auto const & arm = *ports[i].arm;
    AddRibbon(Curve(ports[i].point, -arm.m_directionAway, targets[i], wide.m_directionAway, factor),
              -arm.m_normalAwayPerMeter, wide.m_normalAwayPerMeter, ports[i].width, widths[i], casing, clip, triangles);
  }
  return true;
}

void AddThroughRoads(feature::RoadJunction const & junction, double casing, m2::RectD const & clip,
                     std::vector<m2::PointD> & triangles)
{
  struct Pair
  {
    size_t a, b;
    double score;
  };
  std::vector<Pair> pairs;
  for (size_t a = 0; a < junction.m_arms.size(); ++a)
    for (size_t b = a + 1; b < junction.m_arms.size(); ++b)
    {
      auto const & first = junction.m_arms[a];
      auto const & second = junction.m_arms[b];
      double const dot = DotProduct(first.m_nodeDirectionAway, second.m_nodeDirectionAway);
      if (dot < -0.90)
        pairs.push_back(
            {a, b,
             std::max(first.WidthMeters(), second.WidthMeters()) * (first.m_featureId == second.m_featureId ? 100 : 1) -
                 dot});
    }
  std::sort(pairs.begin(), pairs.end(), [](auto const & a, auto const & b) { return a.score > b.score; });
  std::vector<bool> used(junction.m_arms.size(), false);
  for (auto const & pair : pairs)
  {
    if (used[pair.a] || used[pair.b])
      continue;
    used[pair.a] = used[pair.b] = true;
    auto const & a = junction.m_arms[pair.a];
    auto const & b = junction.m_arms[pair.b];
    AddRibbon(Curve(a.m_position, -a.m_directionAway, b.m_position, b.m_directionAway), -a.m_normalAwayPerMeter,
              b.m_normalAwayPerMeter, a.WidthMeters(), b.WidthMeters(), casing, clip, triangles);
  }
}

m2::PointD LaneBoundary(Arm const & arm, size_t boundary)
{
  double offset = arm.WidthMeters() / 2;
  for (size_t i = 0; i < boundary; ++i)
    offset -= arm.m_widthsCm[i] * 0.01;
  return arm.m_position + arm.m_normalAwayPerMeter * ((arm.m_forward ? 1 : -1) * offset);
}

m2::PointD LaneCenter(Arm const & arm, size_t lane)
{
  return (LaneBoundary(arm, lane) + LaneBoundary(arm, lane + 1)) * 0.5;
}

bool AllowsTurn(uint16_t turns, m2::PointD const & incoming, m2::PointD const & outgoing)
{
  if (turns == 0)
    return true;
  double const angle = std::atan2(CrossProduct(incoming, outgoing), DotProduct(incoming, outgoing));
  using D = feature::RoadDetails;
  if (std::abs(angle) > math::pi * 0.85)
    return turns & D::Reverse;
  bool const through = std::abs(angle) < math::pi / 4 && (turns & (D::Through | D::MergeLeft | D::MergeRight));
  bool const left = angle > math::pi / 360 && (turns & (D::Left | D::SlightLeft | D::SharpLeft));
  bool const right = angle < -math::pi / 360 && (turns & (D::Right | D::SlightRight | D::SharpRight));
  return through || left || right;
}

std::vector<size_t> OrderedArms(feature::RoadJunction const & junction)
{
  std::vector<size_t> order(junction.m_arms.size());
  std::iota(order.begin(), order.end(), 0);
  std::sort(order.begin(), order.end(), [&](size_t a, size_t b)
  {
    auto const p = junction.m_arms[a].m_position - junction.m_center;
    auto const q = junction.m_arms[b].m_position - junction.m_center;
    return std::atan2(p.y, p.x) < std::atan2(q.y, q.x);
  });
  return order;
}

std::vector<m2::PointD> Outline(feature::RoadJunction const & junction, double casing)
{
  auto const order = OrderedArms(junction);
  std::vector<m2::PointD> boundary;
  for (size_t i = 0; i < order.size(); ++i)
  {
    auto const & arm = junction.m_arms[order[i]];
    auto const & next = junction.m_arms[order[(i + 1) % order.size()]];
    double const half = (arm.WidthMeters() + casing) / 2;
    double const nextHalf = (next.WidthMeters() + casing) / 2;
    // A tiny overlap covers final-MWM coordinate quantization at the shared cut plane.
    auto const center = arm.m_position + arm.m_directionAway * (0.1 * arm.m_normalAwayPerMeter.Length());
    auto const nextCenter = next.m_position + next.m_directionAway * (0.1 * next.m_normalAwayPerMeter.Length());
    auto const right = center - arm.m_normalAwayPerMeter * half;
    auto const left = center + arm.m_normalAwayPerMeter * half;
    auto const nextRight = nextCenter - next.m_normalAwayPerMeter * nextHalf;
    boundary.push_back(right);
    auto const edge = Curve(left, -arm.m_directionAway, nextRight, next.m_directionAway);
    boundary.insert(boundary.end(), edge.begin(), edge.end());
  }
  return boundary;
}

void AddSeparator(std::vector<m2::PointD> const & path, m2::RectD const & clip, std::vector<m2::PointD> & triangles)
{
  RoadDetailGeometry geometry(path);
  if (!geometry.IsValid())
    return;
  auto line = geometry.Separator(clip);
  triangles.insert(triangles.end(), line.begin(), line.end());
}
}  // namespace

std::vector<m2::PointD> IntersectRoadTriangles(std::vector<m2::PointD> const & markings,
                                               std::vector<m2::PointD> const & surface)
{
  std::vector<m2::PointD> result;
  for (size_t i = 0; i < markings.size(); i += 3)
  {
    m2::RectD markBounds;
    for (size_t k = 0; k < 3; ++k)
      markBounds.Add(markings[i + k]);
    for (size_t j = 0; j < surface.size(); j += 3)
    {
      m2::RectD surfaceBounds;
      for (size_t k = 0; k < 3; ++k)
        surfaceBounds.Add(surface[j + k]);
      if (!surfaceBounds.IsIntersect(markBounds))
        continue;
      if (std::abs(CrossProduct(surface[j + 1] - surface[j], surface[j + 2] - surface[j])) < 1e-20)
        continue;
      std::vector<m2::PointD> polygon(markings.begin() + i, markings.begin() + i + 3);
      for (size_t edge = 0; edge < 3 && !polygon.empty(); ++edge)
      {
        auto const a = surface[j + edge];
        auto const direction = surface[j + (edge + 1) % 3] - a;
        std::vector<m2::PointD> clipped;
        auto previous = polygon.back();
        double previousSide = CrossProduct(direction, previous - a);
        for (auto const & point : polygon)
        {
          double const side = CrossProduct(direction, point - a);
          bool const inside = side <= 1e-18, previousInside = previousSide <= 1e-18;
          if (inside != previousInside)
            clipped.push_back(previous +
                              (point - previous) * std::clamp(previousSide / (previousSide - side), 0.0, 1.0));
          if (inside)
            clipped.push_back(point);
          previous = point;
          previousSide = side;
        }
        polygon.swap(clipped);
      }
      for (size_t k = 2; k < polygon.size(); ++k)
        if (std::abs(CrossProduct(polygon[k - 1] - polygon[0], polygon[k] - polygon[0])) > 1e-20)
          result.insert(result.end(), {polygon[0], polygon[k - 1], polygon[k]});
    }
  }
  return result;
}

void RoadDecks::Add(int layer, std::vector<m2::PointD> triangles)
{
  Deck deck{layer, {}, std::move(triangles)};
  for (auto const & point : deck.m_triangles)
    deck.m_bounds.Add(point);
  for (size_t i = 0; i < deck.m_triangles.size(); i += 3)
    if (CrossProduct(deck.m_triangles[i + 1] - deck.m_triangles[i], deck.m_triangles[i + 2] - deck.m_triangles[i]) > 0)
      std::swap(deck.m_triangles[i + 1], deck.m_triangles[i + 2]);
  m_decks.push_back(std::move(deck));
}

std::vector<m2::PointD> RoadDecks::Mask(feature::RoadJunction const & junction, int layer, m2::RectD const & clip) const
{
  auto localClip = junction.Bounds();
  if (!localClip.Intersect(clip))
    return {};
  std::vector<m2::PointD> result;
  for (auto const & deck : m_decks)
  {
    if (deck.m_layer != layer || !deck.m_bounds.IsPointInside(junction.m_center))
      continue;
    bool touches = false;
    for (size_t i = 0; i < deck.m_triangles.size() && !touches; i += 3)
      touches = m2::IsPointInsideTriangle(junction.m_center, deck.m_triangles[i], deck.m_triangles[i + 1],
                                          deck.m_triangles[i + 2]);
    if (!touches)
      continue;
    for (size_t i = 0; i < deck.m_triangles.size(); i += 3)
      m2::ClipTriangleByRect(localClip, deck.m_triangles[i], deck.m_triangles[i + 1], deck.m_triangles[i + 2],
                             [&](auto const & a, auto const & b, auto const & c)
      { result.insert(result.end(), {a, b, c}); });
  }
  return result;
}

std::vector<RoadLaneConnection> BuildRoadLaneConnections(feature::RoadJunction const & junction)
{
  struct Port
  {
    size_t m_arm, m_lane;
    m2::PointD m_point;
  };
  std::vector<Port> incoming, outgoing;
  size_t inArms = 0, outArms = 0;
  for (size_t i = 0; i < junction.m_arms.size(); ++i)
  {
    auto const & arm = junction.m_arms[i];
    if (!arm.m_oneWay || (!arm.m_markings && arm.m_widthsCm.size() != 1))
      return {};
    auto & ports = arm.m_forward ? outgoing : incoming;
    (arm.m_forward ? outArms : inArms)++;
    for (size_t lane = 0; lane < arm.m_widthsCm.size(); ++lane)
      ports.push_back({i, lane, LaneCenter(arm, lane)});
  }
  if (incoming.empty() || incoming.size() != outgoing.size() || (inArms != 1 && outArms != 1))
    return {};
  auto const & reference = junction.m_arms[(outArms == 1 ? outgoing : incoming).front().m_arm];
  auto const left = reference.m_normalAwayPerMeter * (reference.m_forward ? 1 : -1);
  auto const order = [&](Port const & a, Port const & b)
  {
    if (a.m_arm == b.m_arm)
      return a.m_lane < b.m_lane;
    auto const & first = junction.m_arms[a.m_arm];
    auto const & second = junction.m_arms[b.m_arm];
    // Order road bundles first. Sorting individual lane centres interleaves a close ramp
    // with the main carriageway and assigns its turn to the wrong lane.
    double const firstSide = DotProduct(first.m_nodeDirectionAway, left);
    double const secondSide = DotProduct(second.m_nodeDirectionAway, left);
    if (firstSide != secondSide)
      return firstSide > secondSide;
    double const position = DotProduct(first.m_position - second.m_position, left);
    return position != 0 ? position > 0 : a.m_arm < b.m_arm;
  };
  if (inArms != 1)
    std::sort(incoming.begin(), incoming.end(), order);
  if (outArms != 1)
    std::sort(outgoing.begin(), outgoing.end(), order);
  auto const clearance = [&](std::vector<Port> const & ports, std::vector<Port> const & opposite)
  {
    double factor = 1;
    auto const normal = left.Normalize();
    for (size_t i = 1; i < ports.size(); ++i)
    {
      auto const & a = ports[i - 1];
      auto const & b = ports[i];
      if (a.m_arm == b.m_arm)
        continue;
      auto const & first = junction.m_arms[a.m_arm];
      auto const & second = junction.m_arms[b.m_arm];
      auto const ca = a.m_point - first.m_directionAway * ((opposite[i - 1].m_point - a.m_point).Length() * 0.4);
      auto const cb = b.m_point - second.m_directionAway * ((opposite[i].m_point - b.m_point).Length() * 0.4);
      double const gap = DotProduct(a.m_point - b.m_point, normal);
      double const controlGap = DotProduct(ca - cb, normal);
      double const laneGap = (first.m_widthsCm[a.m_lane] + second.m_widthsCm[b.m_lane]) * 0.005 *
                             std::max(first.m_normalAwayPerMeter.Length(), second.m_normalAwayPerMeter.Length());
      if (controlGap < laneGap && controlGap < gap)
        factor = std::min(factor, std::clamp((gap - laneGap) / (gap - controlGap), 0.0, 1.0));
    }
    return factor;
  };
  double const inControl = inArms == 1 ? 1 : clearance(incoming, outgoing);
  double const outControl = outArms == 1 ? 1 : clearance(outgoing, incoming);
  std::vector<RoadLaneConnection> connections;
  for (size_t i = 0; i < incoming.size(); ++i)
  {
    auto const & in = incoming[i];
    auto const & out = outgoing[i];
    auto const & inArm = junction.m_arms[in.m_arm];
    auto const & outArm = junction.m_arms[out.m_arm];
    if (!AllowsTurn(inArm.m_turns[in.m_lane], -inArm.m_directionAway, outArm.m_directionAway))
      return {};
    RoadLaneConnection connection;
    connection.m_inArm = in.m_arm;
    connection.m_inLane = in.m_lane;
    connection.m_outArm = out.m_arm;
    connection.m_outLane = out.m_lane;
    m2::MetricPolyline path(
        Curve(in.m_point, -inArm.m_directionAway, out.m_point, outArm.m_directionAway, inControl, outControl));
    if (!path.IsValid())
      return {};
    path.SetFrame(0, in.m_point, -inArm.m_normalAwayPerMeter);
    path.SetFrame(path.Length(), out.m_point, outArm.m_normalAwayPerMeter);
    for (size_t point = 0; point <= 20; ++point)
    {
      double const t = point / 20.0;
      auto const frame = path.Sample(path.Length() * t);
      double const width = std::lerp(inArm.m_widthsCm[in.m_lane] * 0.01, outArm.m_widthsCm[out.m_lane] * 0.01, t);
      connection.m_path.push_back(frame.m_position);
      connection.m_leftEdge.push_back(frame.m_position + frame.m_leftPerMeter * (width / 2));
      connection.m_rightEdge.push_back(frame.m_position - frame.m_leftPerMeter * (width / 2));
    }
    connections.push_back(std::move(connection));
  }
  for (size_t i = 1; i < connections.size(); ++i)
  {
    auto & a = connections[i - 1];
    auto & b = connections[i];
    size_t const first = a.m_inArm == b.m_inArm ? 0 : a.m_path.size() / 2;
    size_t const last = a.m_outArm == b.m_outArm ? a.m_path.size() : a.m_path.size() / 2 + 1;
    // Adjacent lanes share one boundary wherever they belong to the same carriageway.
    // Both the fill and the divider consume these exact vertices.
    for (size_t point = first; point < last; ++point)
    {
      auto const boundary = (a.m_rightEdge[point] + b.m_leftEdge[point]) * 0.5;
      a.m_rightEdge[point] = b.m_leftEdge[point] = boundary;
    }
  }
  return connections;
}

static std::vector<m2::PointD> BuildJunctionSurface(feature::RoadJunction const & junction, m2::RectD const & clip,
                                                    double casingMeters,
                                                    std::vector<RoadLaneConnection> const & connections)
{
  if (junction.HasContinuation())
    return {};
  auto boundary = Outline(junction, casingMeters);
  double const units =
      0.00001 / mercator::DistanceOnEarth(junction.m_center, junction.m_center + m2::PointD(0.00001, 0));
  for (auto & point : boundary)
    point = (point - junction.m_center) / units;
  auto const deleter = [](TESStesselator * tess) { tessDeleteTess(tess); };
  std::unique_ptr<TESStesselator, decltype(deleter)> tess(tessNewTess(nullptr), deleter);
  tessAddContour(tess.get(), 2, boundary.data(), sizeof(m2::PointD), static_cast<int>(boundary.size()));
  // Keep the actual cut-plane edges at merges: a convex hull widens the narrow arm into a step.
  CHECK(tessTesselate(tess.get(), TESS_WINDING_NONZERO, TESS_POLYGONS, 3, 2, nullptr), ());
  auto const * vertices = tessGetVertices(tess.get());
  auto const * indices = tessGetElements(tess.get());
  std::vector<m2::PointD> triangles;
  for (int i = 0; i < tessGetElementCount(tess.get()); ++i)
  {
    std::array<m2::PointD, 3> triangle;
    for (size_t k = 0; k < 3; ++k)
    {
      auto const index = indices[i * 3 + k];
      CHECK_NOT_EQUAL(index, TESS_UNDEF, ());
      triangle[k] = junction.m_center + m2::PointD(vertices[index * 2], vertices[index * 2 + 1]) * units;
    }
    if (CrossProduct(triangle[1] - triangle[0], triangle[2] - triangle[0]) > 0)
      std::swap(triangle[1], triangle[2]);
    m2::ClipTriangleByRect(clip, triangle[0], triangle[1], triangle[2],
                           [&](auto const & a, auto const & b, auto const & c)
    { triangles.insert(triangles.end(), {a, b, c}); });
  }
  // Lane ribbons retain both lanes through a balanced Y junction, independent of traffic direction.
  if (!AddBalancedMergeLanes(junction, casingMeters, clip, connections, triangles))
    AddThroughRoads(junction, casingMeters, clip, triangles);
  return triangles;
}

std::vector<m2::PointD> BuildRoadJunctionSurface(feature::RoadJunction const & junction, m2::RectD const & clip,
                                                 double casingMeters)
{
  return BuildJunctionSurface(junction, clip, casingMeters, BuildRoadLaneConnections(junction));
}

std::vector<m2::PointD> BuildRoadJunctionInfill(feature::RoadJunction const & junction,
                                                JunctionGeometryGetter const & geometry, m2::RectD const & clip)
{
  if (!geometry || junction.HasContinuation() || junction.m_arms.size() < 3)
    return {};
  auto const bounds = junction.Bounds();
  std::vector<feature::RoadJunction> nodes{junction};
  for (auto const & arm : junction.m_arms)
  {
    auto const road = geometry(arm.m_featureId);
    if (!road || !arm.m_featureEndpoint)
      continue;
    for (auto const & next : road->EndJunctions())
    {
      if (next.m_center == junction.m_center || !bounds.IsPointInside(next.m_center))
        continue;
      auto const other = std::find_if(next.m_arms.begin(), next.m_arms.end(), [&](auto const & a)
      { return a.m_featureId == arm.m_featureId && a.m_forward != arm.m_forward; });
      if (other == next.m_arms.end() || road->Length() - arm.m_cutDistance - other->m_cutDistance > arm.WidthMeters())
        continue;
      // A compound node is drawn once, by its lowest owner ID.
      if (next.m_ownerFeatureId < junction.m_ownerFeatureId ||
          (next.m_ownerFeatureId == junction.m_ownerFeatureId && next.m_center < junction.m_center))
        return {};
      if (std::none_of(nodes.begin(), nodes.end(), [&](auto const & n) { return n.m_center == next.m_center; }))
        nodes.push_back(next);
    }
  }
  if (nodes.size() < 2)
    return {};

  std::vector<m2::PointD> pavement;
  auto const append = [&](std::vector<m2::PointD> const & mesh)
  { pavement.insert(pavement.end(), mesh.begin(), mesh.end()); };
  std::set<uint32_t> roads;
  double laneWidth = std::numeric_limits<double>::max();
  for (auto const & node : nodes)
  {
    append(BuildRoadJunctionSurface(node, bounds));
    for (auto const & arm : node.m_arms)
    {
      for (auto width : arm.m_widthsCm)
        laneWidth = std::min(laneWidth, width * 0.01);
      if (roads.insert(arm.m_featureId).second)
        if (auto const road = geometry(arm.m_featureId))
          append(road->Surface(arm.WidthMeters(), bounds));
    }
  }
  double const units =
      0.00001 / mercator::DistanceOnEarth(junction.m_center, junction.m_center + m2::PointD(0.00001, 0));
  for (auto & p : pavement)
    p = (p - junction.m_center) / units;
  auto const deleter = [](TESStesselator * t) { tessDeleteTess(t); };
  std::unique_ptr<TESStesselator, decltype(deleter)> unionTess(tessNewTess(nullptr), deleter);
  for (size_t i = 0; i < pavement.size(); i += 3)
    tessAddContour(unionTess.get(), 2, pavement.data() + i, sizeof(m2::PointD), 3);
  TESSreal const normal[]{0, 0, 1};
  CHECK(tessTesselate(unionTess.get(), TESS_WINDING_NONZERO, TESS_BOUNDARY_CONTOURS, 0, 2, normal), ());
  auto const * vertices = tessGetVertices(unionTess.get());
  auto const * contours = tessGetElements(unionTess.get());
  std::vector<m2::PointD> result;
  for (int i = 0; i < tessGetElementCount(unionTess.get()); ++i)
  {
    auto const start = contours[2 * i], count = contours[2 * i + 1];
    std::vector<m2::PointD> ring;
    for (int k = 0; k < count; ++k)
      ring.emplace_back(vertices[2 * (start + k)], vertices[2 * (start + k) + 1]);
    double area = 0, perimeter = 0;
    for (size_t k = 0; k < ring.size(); ++k)
    {
      auto const & a = ring[k];
      auto const & b = ring[(k + 1) % ring.size()];
      area += CrossProduct(a, b);
      perimeter += (b - a).Length();
    }
    // With an explicit +Z normal, inner contours are clockwise. Close only narrow
    // residual pockets, not large islands or the exterior between separate roads.
    if (area >= 0 || -area / 2 > perimeter * laneWidth / 4)
      continue;
    std::unique_ptr<TESStesselator, decltype(deleter)> fill(tessNewTess(nullptr), deleter);
    tessAddContour(fill.get(), 2, ring.data(), sizeof(m2::PointD), static_cast<int>(ring.size()));
    CHECK(tessTesselate(fill.get(), TESS_WINDING_NONZERO, TESS_POLYGONS, 3, 2, normal), ());
    auto const * points = tessGetVertices(fill.get());
    auto const * indices = tessGetElements(fill.get());
    for (int t = 0; t < tessGetElementCount(fill.get()); ++t)
    {
      std::array<m2::PointD, 3> triangle;
      for (size_t k = 0; k < 3; ++k)
      {
        auto const index = indices[3 * t + k];
        CHECK_NOT_EQUAL(index, TESS_UNDEF, ());
        triangle[k] = junction.m_center + m2::PointD(points[2 * index], points[2 * index + 1]) * units;
      }
      if (CrossProduct(triangle[1] - triangle[0], triangle[2] - triangle[0]) > 0)
        std::swap(triangle[1], triangle[2]);
      m2::ClipTriangleByRect(clip, triangle[0], triangle[1], triangle[2],
                             [&](auto const & a, auto const & b, auto const & c)
      { result.insert(result.end(), {a, b, c}); });
    }
  }
  return result;
}

std::vector<m2::PointD> BuildRoadJunctionFillets(feature::RoadJunction const & junction, uint32_t featureId,
                                                 JunctionGeometryGetter const & geometry, m2::RectD const & clip,
                                                 double casingMeters)
{
  if (!junction.HasContinuation() || junction.m_arms.size() < 3 || !geometry)
    return {};
  auto const order = OrderedArms(junction);
  auto const isThrough = [&](size_t arm) { return arm == junction.m_continuationA || arm == junction.m_continuationB; };
  double const units =
      0.00001 / mercator::DistanceOnEarth(junction.m_center, junction.m_center + m2::PointD(0.00001, 0));
  std::vector<m2::PointD> result;
  for (size_t i = 0; i < order.size(); ++i)
  {
    size_t const a = order[i], b = order[(i + 1) % order.size()];
    if (isThrough(a) && isThrough(b))
      continue;
    auto const & arm = junction.m_arms[a];
    auto const & next = junction.m_arms[b];
    uint32_t const owner = isThrough(a) ? next.m_featureId
                         : isThrough(b) ? arm.m_featureId
                                        : std::min(arm.m_featureId, next.m_featureId);
    if (owner != featureId)
      continue;
    auto const firstGeometry = geometry(arm.m_featureId), secondGeometry = geometry(next.m_featureId);
    if (!firstGeometry || !secondGeometry || !firstGeometry->IsValid() || !secondGeometry->IsValid())
      continue;
    auto first = firstGeometry->JunctionEdge(junction, a, true, casingMeters);
    auto second = secondGeometry->JunctionEdge(junction, b, false, casingMeters);
    for (auto * edge : {&first, &second})
      for (auto & point : *edge)
        point = (point - junction.m_center) / units;

    // Find the actual corner of the rendered offset polylines, including roundabout curvature.
    size_t firstSegment = 0, secondSegment = 0;
    m2::PointD corner;
    for (size_t j = 1; j < first.size() && firstSegment == 0; ++j)
      for (size_t k = 1; k < second.size(); ++k)
      {
        auto const da = first[j] - first[j - 1], db = second[k] - second[k - 1];
        double const cross = CrossProduct(da, db);
        if (cross < 1e-8)
          continue;
        auto const delta = second[k - 1] - first[j - 1];
        double const t = CrossProduct(delta, db) / cross, u = CrossProduct(delta, da) / cross;
        if (t < 0 || t > 1 || u < 0 || u > 1)
          continue;
        corner = first[j - 1] + da * t;
        firstSegment = j;
        secondSegment = k;
        break;
      }
    if (firstSegment == 0)
      continue;
    double const radius = std::min({4.0, arm.WidthMeters(), next.WidthMeters()});
    auto const edgePart = [&](std::vector<m2::PointD> const & edge, size_t segment)
    {
      std::vector<m2::PointD> part{corner};
      double remaining = radius;
      for (size_t j = segment; j < edge.size(); ++j)
      {
        auto const delta = edge[j] - part.back();
        double const length = delta.Length();
        if (length < 1e-6)
          continue;
        if (length >= remaining)
        {
          part.push_back(part.back() + delta * (remaining / length));
          break;
        }
        part.push_back(edge[j]);
        remaining -= length;
      }
      return part;
    };
    auto const left = edgePart(first, firstSegment), right = edgePart(second, secondSegment);
    if (left.size() < 2 || right.size() < 2)
      continue;
    auto const da = (left.back() - left[left.size() - 2]).Normalize();
    auto const db = (right.back() - right[right.size() - 2]).Normalize();
    auto const chord = right.back() - left.back();
    if (DotProduct(-da, chord) <= 0 || DotProduct(db, chord) <= 0)
      continue;
    auto polygon = left;
    auto const curve = Curve(left.back(), -da, right.back(), db);
    polygon.insert(polygon.end(), curve.begin() + 1, curve.end());
    polygon.insert(polygon.end(), right.rbegin() + 1, right.rend());

    auto const deleter = [](TESStesselator * tess) { tessDeleteTess(tess); };
    std::unique_ptr<TESStesselator, decltype(deleter)> tess(tessNewTess(nullptr), deleter);
    tessAddContour(tess.get(), 2, polygon.data(), sizeof(m2::PointD), static_cast<int>(polygon.size()));
    CHECK(tessTesselate(tess.get(), TESS_WINDING_ODD, TESS_POLYGONS, 3, 2, nullptr), ());
    auto const * vertices = tessGetVertices(tess.get());
    auto const * indices = tessGetElements(tess.get());
    for (int j = 0; j < tessGetElementCount(tess.get()); ++j)
    {
      std::array<m2::PointD, 3> triangle;
      for (size_t k = 0; k < 3; ++k)
      {
        auto const index = indices[j * 3 + k];
        CHECK_NOT_EQUAL(index, TESS_UNDEF, ());
        triangle[k] = junction.m_center + m2::PointD(vertices[index * 2], vertices[index * 2 + 1]) * units;
      }
      if (CrossProduct(triangle[1] - triangle[0], triangle[2] - triangle[0]) > 0)
        std::swap(triangle[1], triangle[2]);
      m2::ClipTriangleByRect(clip, triangle[0], triangle[1], triangle[2],
                             [&](auto const & p0, auto const & p1, auto const & p2)
      { result.insert(result.end(), {p0, p1, p2}); });
    }
  }
  return result;
}

std::vector<m2::PointD> BuildElevatedRoadConnection(feature::RoadJunction const & junction,
                                                    JunctionGeometryGetter const & geometry, m2::RectD const & clip,
                                                    bool markings, std::vector<m2::PointD> const & deckMask)
{
  if (!geometry)
    return {};
  std::vector<m2::PointD> result;
  for (size_t index = 0; index < junction.m_arms.size(); ++index)
  {
    if (junction.HasContinuation() && index != junction.m_continuationA && index != junction.m_continuationB)
      continue;
    auto const & arm = junction.m_arms[index];
    auto const road = geometry(arm.m_featureId);
    if (!road || !road->IsValid())
      continue;
    double const reach = !deckMask.empty()
                           ? mercator::DistanceOnEarth(junction.Bounds().LeftBottom(), junction.Bounds().RightTop())
                       : junction.HasContinuation() ? 5.0
                                                    : arm.m_cutDistance + 1.0;
    auto const patch = road->JunctionPatch(junction, index, reach, markings, clip);
    auto const visible = deckMask.empty() ? patch : IntersectRoadTriangles(patch, deckMask);
    result.insert(result.end(), visible.begin(), visible.end());
  }
  return result;
}

std::vector<m2::PointD> BuildRoadBranchMask(feature::RoadJunction const & junction, uint32_t featureId,
                                            JunctionGeometryGetter const & geometry, m2::RectD const & clip)
{
  if (!geometry)
    return {};
  if (!junction.HasContinuation())
  {
    std::vector<m2::PointD> mask;
    // A short link's width transition can reach the through road at its other end.
    // Apply that road's boundary to the transition owner as well as the link itself.
    if (junction.m_arms.size() == 2)
      for (auto const & arm : junction.m_arms)
        if (auto const road = geometry(arm.m_featureId))
          for (auto const & adjacent : road->BranchJunctions())
            if (adjacent.Bounds().IsIntersect(junction.Bounds()))
            {
              auto const boundary = BuildRoadBranchMask(adjacent, featureId, geometry, clip);
              mask.insert(mask.end(), boundary.begin(), boundary.end());
            }
    if (featureId == junction.m_ownerFeatureId)
      return mask;
    auto const surface = BuildRoadJunctionSurface(junction, clip);
    mask.insert(mask.end(), surface.begin(), surface.end());
    auto localClip = junction.Bounds();
    if (!localClip.Intersect(clip))
      return mask;
    auto const owner = geometry(junction.m_ownerFeatureId);
    if (owner && owner->IsValid())
    {
      auto const arm = std::find_if(junction.m_arms.begin(), junction.m_arms.end(),
                                    [&](auto const & a) { return a.m_featureId == junction.m_ownerFeatureId; });
      ASSERT(arm != junction.m_arms.end(), ());
      auto const surface = owner->Surface(arm->WidthMeters(), localClip);
      mask.insert(mask.end(), surface.begin(), surface.end());
    }
    return mask;
  }
  auto const & a = junction.m_arms[junction.m_continuationA];
  auto const & b = junction.m_arms[junction.m_continuationB];
  if (featureId == a.m_featureId || featureId == b.m_featureId)
    return {};
  std::vector<m2::PointD> mask;
  auto localClip = junction.Bounds();
  if (!localClip.Intersect(clip))
    return {};
  uint32_t previous = std::numeric_limits<uint32_t>::max();
  for (size_t index : {junction.m_continuationA, junction.m_continuationB})
  {
    auto const & arm = junction.m_arms[index];
    if (arm.m_featureId == previous)
      continue;
    previous = arm.m_featureId;
    auto const road = geometry(arm.m_featureId);
    if (!road || !road->IsValid())
      continue;
    // At an acute merge the ribbons overlap before the centerline reaches the junction cut plane.
    auto const patch = road->Surface(arm.WidthMeters(), localClip);
    mask.insert(mask.end(), patch.begin(), patch.end());
  }
  return mask;
}

std::vector<m2::PointD> SubtractRoadTriangles(std::vector<m2::PointD> triangles, std::vector<m2::PointD> const & mask)
{
  for (size_t m = 0; m < mask.size() && !triangles.empty(); m += 3)
  {
    if (std::abs(CrossProduct(mask[m + 1] - mask[m], mask[m + 2] - mask[m])) < 1e-20)
      continue;
    m2::RectD bounds;
    for (size_t k = 0; k < 3; ++k)
      bounds.Add(mask[m + k]);
    std::vector<m2::PointD> result;
    for (size_t i = 0; i < triangles.size(); i += 3)
    {
      m2::RectD triangleBounds;
      for (size_t k = 0; k < 3; ++k)
        triangleBounds.Add(triangles[i + k]);
      if (!bounds.IsIntersect(triangleBounds))
      {
        result.insert(result.end(), triangles.begin() + i, triangles.begin() + i + 3);
        continue;
      }
      std::vector<m2::PointD> remainder(triangles.begin() + i, triangles.begin() + i + 3);
      // Each outside fragment is retained once; only the intersection with all three half-planes is removed.
      for (size_t edge = 0; edge < 3 && !remainder.empty(); ++edge)
      {
        auto const origin = mask[m + edge];
        auto const direction = mask[m + (edge + 1) % 3] - origin;
        std::vector<m2::PointD> inside, outside;
        auto previous = remainder.back();
        double previousSide = CrossProduct(direction, previous - origin);
        for (auto const & point : remainder)
        {
          double const side = CrossProduct(direction, point - origin);
          if ((side <= 0) != (previousSide <= 0))
          {
            auto const crossing = previous + (point - previous) * (previousSide / (previousSide - side));
            inside.push_back(crossing);
            outside.push_back(crossing);
          }
          (side <= 0 ? inside : outside).push_back(point);
          previous = point;
          previousSide = side;
        }
        for (size_t k = 2; k < outside.size(); ++k)
          if (std::abs(CrossProduct(outside[k - 1] - outside[0], outside[k] - outside[0])) > 1e-20)
            result.insert(result.end(), {outside[0], outside[k - 1], outside[k]});
        remainder = std::move(inside);
      }
    }
    triangles = std::move(result);
  }
  return triangles;
}

static std::vector<m2::PointD> BuildJunctionMarkings(feature::RoadJunction const & junction, m2::RectD const & clip,
                                                     std::vector<m2::PointD> const & surface,
                                                     std::vector<RoadLaneConnection> const & connections)
{
  std::vector<m2::PointD> triangles;
  if (junction.HasContinuation())
    return {};
  for (size_t i = 1; i < connections.size(); ++i)
  {
    auto const & a = connections[i - 1];
    auto const & b = connections[i];
    std::vector<m2::PointD> boundary;
    // A divider only belongs on the common carriageway, not across an island between separate arms.
    size_t const first = a.m_inArm == b.m_inArm ? 0 : a.m_path.size() / 2;
    size_t const last = a.m_outArm == b.m_outArm ? a.m_path.size() : a.m_path.size() / 2 + 1;
    for (size_t j = first; j < last; ++j)
    {
      // Derive paint from the same lane edges used by the pavement ribbons.
      boundary.push_back((a.m_rightEdge[j] + b.m_leftEdge[j]) * 0.5);
    }
    AddSeparator(boundary, clip, triangles);
  }
  if (!connections.empty() || junction.m_arms.size() != 2)
    return IntersectRoadTriangles(triangles, surface);
  auto const & a = junction.m_arms[0];
  auto const & b = junction.m_arms[1];
  if (!a.m_oneWay || !b.m_oneWay || !a.m_markings || !b.m_markings || a.m_forward == b.m_forward)
    return IntersectRoadTriangles(triangles, surface);
  auto const & in = a.m_forward ? b : a;
  auto const & out = a.m_forward ? a : b;
  if (in.m_widthsCm.size() == out.m_widthsCm.size())
    return IntersectRoadTriangles(triangles, surface);
  auto const & wider = in.m_widthsCm.size() > out.m_widthsCm.size() ? in : out;
  using D = feature::RoadDetails;
  bool const alignRight = (wider.m_turns.front() & (D::Left | D::SlightLeft | D::SharpLeft)) &&
                          !(wider.m_turns.back() & (D::Right | D::SlightRight | D::SharpRight));
  m2::MetricPolyline center(Curve(in.m_position, -in.m_directionAway, out.m_position, out.m_directionAway));
  if (!center.IsValid())
    return {};
  center.SetFrame(0, in.m_position, -in.m_normalAwayPerMeter);
  center.SetFrame(center.Length(), out.m_position, out.m_normalAwayPerMeter);
  for (size_t i = 1; i < wider.m_widthsCm.size(); ++i)
  {
    auto const offset = [&](Arm const & arm)
    {
      auto const n = arm.m_widthsCm.size();
      size_t const boundary = alignRight ? (i >= n ? 0 : n - i) : std::min(i, n);
      double value = arm.WidthMeters() / 2;
      for (size_t lane = 0; lane < boundary; ++lane)
        value -= arm.m_widthsCm[lane] * 0.01;
      return value;
    };
    std::vector<m2::PointD> boundary;
    for (size_t point = 0; point < center.Points().size(); ++point)
      boundary.push_back(center.Points()[point] +
                         center.Normals()[point] *
                             std::lerp(offset(in), offset(out), center.Distances()[point] / center.Length()));
    AddSeparator(boundary, clip, triangles);
  }
  return IntersectRoadTriangles(triangles, surface);
}
RoadJunctionMesh BuildRoadJunctionMesh(feature::RoadJunction const & junction, m2::RectD const & clip)
{
  auto const connections = BuildRoadLaneConnections(junction);
  RoadJunctionMesh result;
  result.m_surface = BuildJunctionSurface(junction, clip, 0, connections);
  result.m_markings = BuildJunctionMarkings(junction, clip, result.m_surface, connections);
  return result;
}

std::vector<m2::PointD> BuildRoadJunctionMarkings(feature::RoadJunction const & junction, m2::RectD const & clip)
{
  return BuildRoadJunctionMesh(junction, clip).m_markings;
}

}  // namespace df
