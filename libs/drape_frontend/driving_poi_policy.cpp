#include "drape_frontend/driving_poi_policy.hpp"

#ifdef OMIM_AUTO
#include "indexer/ftypes_matcher.hpp"

namespace df
{
bool IsDrivingPoi(feature::TypesHolder const & types)
{
  static ftypes::BaseCheckerEx const checker({{"amenity", "fuel"},
                                              {"amenity", "charging_station"},
                                              {"amenity", "parking"},
                                              {"amenity", "parking_entrance"},
                                              {"highway", "services"},
                                              {"highway", "rest_area"},
                                              {"highway", "motorway_junction"},
                                              {"highway", "speed_camera"},
                                              {"barrier", "toll_booth"}});
  return checker(types);
}
}  // namespace df
#endif
