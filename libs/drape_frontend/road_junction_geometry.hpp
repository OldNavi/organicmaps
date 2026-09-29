#pragma once

#include "indexer/road_junctions.hpp"

#include "geometry/rect2d.hpp"

#include <functional>
#include <memory>

namespace df
{
class RoadDetailGeometry;
using JunctionGeometryGetter = std::function<std::shared_ptr<RoadDetailGeometry const>(uint32_t)>;

class RoadDecks
{
public:
  void Add(int layer, std::vector<m2::PointD> triangles);
  std::vector<m2::PointD> Mask(feature::RoadJunction const & junction, int layer, m2::RectD const & clip) const;

private:
  struct Deck
  {
    int m_layer;
    m2::RectD m_bounds;
    std::vector<m2::PointD> m_triangles;
  };
  std::vector<Deck> m_decks;
};

struct RoadLaneConnection
{
  size_t m_inArm = 0, m_inLane = 0, m_outArm = 0, m_outLane = 0;
  std::vector<m2::PointD> m_path;
  std::vector<m2::PointD> m_leftEdge, m_rightEdge;
};

struct RoadJunctionMesh
{
  std::vector<m2::PointD> m_surface;
  std::vector<m2::PointD> m_markings;
};
RoadJunctionMesh BuildRoadJunctionMesh(feature::RoadJunction const & junction, m2::RectD const & clip);

std::vector<RoadLaneConnection> BuildRoadLaneConnections(feature::RoadJunction const & junction);
std::vector<m2::PointD> BuildRoadJunctionSurface(feature::RoadJunction const & junction, m2::RectD const & clip,
                                                 double casingMeters = 0);
std::vector<m2::PointD> BuildRoadJunctionFillets(feature::RoadJunction const & junction, uint32_t featureId,
                                                 JunctionGeometryGetter const & geometry, m2::RectD const & clip,
                                                 double casingMeters = 0);
std::vector<m2::PointD> BuildElevatedRoadConnection(feature::RoadJunction const & junction,
                                                    JunctionGeometryGetter const & geometry, m2::RectD const & clip,
                                                    bool markings, std::vector<m2::PointD> const & deckMask = {});
std::vector<m2::PointD> BuildRoadJunctionMarkings(feature::RoadJunction const & junction, m2::RectD const & clip);
std::vector<m2::PointD> BuildRoadBranchMask(feature::RoadJunction const & junction, uint32_t featureId,
                                            JunctionGeometryGetter const & geometry, m2::RectD const & clip);
std::vector<m2::PointD> SubtractRoadTriangles(std::vector<m2::PointD> triangles, std::vector<m2::PointD> const & mask);
std::vector<m2::PointD> IntersectRoadTriangles(std::vector<m2::PointD> const & triangles,
                                               std::vector<m2::PointD> const & mask);
}  // namespace df
