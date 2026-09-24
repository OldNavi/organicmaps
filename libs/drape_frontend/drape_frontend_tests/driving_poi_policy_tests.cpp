#include "testing/testing.hpp"

#ifdef OMIM_AUTO
#include "drape_frontend/driving_poi_policy.hpp"
#include "indexer/classificator.hpp"
#include "indexer/classificator_loader.hpp"
#include "indexer/feature_data.hpp"

UNIT_TEST(DrivingPoiPolicy_DoesNotRebuildAtShortStops)
{
  using namespace std::chrono_literals;
  df::DrivingPoiPolicy policy;
  auto const start = df::DrivingPoiPolicy::Clock::time_point{};
  TEST(!policy.Update(0, start), ());
  TEST(policy.Update(10.0 / 3.6, start + 1s), ());
  TEST(policy.IsDriving(), ());
  TEST(!policy.Update(50.0 / 3.6, start + 2s), ("Unchanged mode must not invalidate tiles"));
  TEST(!policy.Update(0, start + 3s), ());
  TEST(!policy.Update(0, start + 62s), ());
  TEST(!policy.Update(5.0 / 3.6, start + 63s), ("Creeping interrupts the stop timer"));
  TEST(!policy.Update(0, start + 64s), ());
  TEST(!policy.Update(-1, start + 123s), ("Unknown speed must not be treated as a stop"));
  TEST(!policy.Update(0, start + 124s), ());
  TEST(!policy.Update(0, start + 183s), ());
  TEST(policy.Update(0, start + 184s), ());
  TEST(!policy.IsDriving(), ());
  TEST(!policy.Update(9.0 / 3.6, start + 185s), ("Hysteresis before re-entering driving mode"));
}

UNIT_TEST(DrivingPoiPolicy_KeepsDrivingServices)
{
  classificator::Load();
  auto matches = [](base::StringIL path)
  {
    feature::TypesHolder types(feature::GeomType::Point);
    types.Add(classif().GetTypeByPath(path));
    return df::IsDrivingPoi(types);
  };
  TEST(matches({"amenity", "fuel"}), ());
  TEST(matches({"amenity", "charging_station", "motorcar"}), ());
  TEST(matches({"amenity", "parking"}), ());
  TEST(matches({"highway", "services"}), ());
  TEST(matches({"barrier", "toll_booth"}), ());
  TEST(!matches({"tourism", "museum"}), ());
  TEST(!matches({"highway", "bus_stop"}), ());
  TEST(!matches({"amenity", "cafe"}), ());
}
#endif
