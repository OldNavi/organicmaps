#include "drape_frontend/driving_poi_policy.hpp"

#ifdef OMIM_AUTO
#include "indexer/ftypes_matcher.hpp"
#include "platform/settings.hpp"

namespace df
{
PoiDensity LoadPoiDensity(bool cluster)
{
  int density = static_cast<int>(PoiDensity::High);
  (void)settings::Get(PoiDensitySetting(cluster), density);
  CHECK(density >= 0 && density <= static_cast<int>(PoiDensity::High), (density));
  return static_cast<PoiDensity>(density);
}

double PoiDensityPadding(PoiDensity density, feature::TypesHolder const & types)
{
  if (!ftypes::IsPoiChecker::Instance()(types) || ftypes::IsLocalityChecker::Instance()(types))
    return 0;
  switch (density)
  {
  case PoiDensity::Low: return 24;
  case PoiDensity::Normal: return 8;
  case PoiDensity::High: return 0;
  }
  UNREACHABLE();
}

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
