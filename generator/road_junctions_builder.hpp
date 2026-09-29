#pragma once

#include "generator/feature_builder.hpp"

#include "indexer/road_junctions.hpp"

#include "geometry/metric_polyline.hpp"

#include <functional>
#include <map>

namespace generator
{
// Endpoint identity comes from OSM node IDs; coincident roads on different levels are not joined.
class RoadJunctionsBuilder
{
public:
  void Add(uint32_t featureId, feature::FeatureBuilder const & feature);
  std::vector<feature::RoadJunction> Build();
  void ForEachRoad(std::function<void(uint32_t, feature::RoadDetails const &)> const & fn) const;

private:
  struct Road
  {
    feature::RoadJunctionArm m_arm;
    feature::RoadDetails m_details;
    uint32_t m_roadType = 0;
    int8_t m_layer = 0;
    std::shared_ptr<m2::MetricPolyline const> m_path;
    std::vector<m2::PointD> m_points;
  };
  struct Endpoint
  {
    std::shared_ptr<Road> m_road;
    double m_nodeDistance = 0;
    m2::PointD m_nodePosition;
    bool m_forward = false;
    bool m_featureEndpoint = false;
  };
  std::map<uint64_t, std::vector<Endpoint>> m_nodes;
  std::map<uint32_t, std::shared_ptr<Road>> m_roads;
  void ResolveInferredWidths();
  void ResolvePlacementTransitions();
};
}  // namespace generator
