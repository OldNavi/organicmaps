#include "testing/testing.hpp"

#ifdef OMIM_AUTO
#include <random>
#include "drape_frontend/driving_poi_policy.hpp"
#include "drape_frontend/user_mark_badge_layout.hpp"
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

UNIT_TEST(PoiDensity_ExcludesRoadsLocalitiesAndBuildings)
{
  classificator::Load();
  auto padding = [](base::StringIL path, df::PoiDensity density)
  {
    feature::TypesHolder types(feature::GeomType::Point);
    types.Add(classif().GetTypeByPath(path));
    return df::PoiDensityPadding(density, types);
  };
  for (auto density : {df::PoiDensity::Low, df::PoiDensity::Normal, df::PoiDensity::High})
  {
    TEST_EQUAL(padding({"highway", "primary"}, density), 0, ());
    TEST_EQUAL(padding({"place", "city"}, density), 0, ());
    TEST_EQUAL(padding({"building"}, density), 0, ());
  }
  TEST_GREATER(padding({"amenity", "fuel"}, df::PoiDensity::Low), padding({"amenity", "fuel"}, df::PoiDensity::Normal),
               ());
  TEST_GREATER(padding({"amenity", "fuel"}, df::PoiDensity::Normal), padding({"amenity", "fuel"}, df::PoiDensity::High),
               ());
  TEST_EQUAL(padding({"amenity", "fuel"}, df::PoiDensity::High), 0, ("Original spacing"));
}

UNIT_TEST(UserMarkBadges_DoNotCoverNeighbouringCameras)
{
  std::vector<df::UserMarkFootprint> symbols{{1, {0, 0, 22, 22}}, {2, {30, 0, 52, 22}}};
  std::vector<df::UserMarkFootprint> badges{{1, {22, 0, 44, 22}}, {2, {52, 0, 76, 22}}};
  std::vector<kml::MarkId> visible;
  df::PlaceUserMarkBadges(symbols, badges, visible);
  TEST_EQUAL(visible, (std::vector<kml::MarkId>{2}), ("Keep both camera icons and only the readable badge"));
  symbols[1].m_rect.Offset(0, 50);
  badges[1].m_rect.Offset(0, 50);
  df::PlaceUserMarkBadges(symbols, badges, visible);
  TEST_EQUAL(visible, (std::vector<kml::MarkId>{1, 2}), ("Both badges return when space is available"));
}

UNIT_TEST(UserMarkBadges_HaveStableOrderAndIgnoreTheirOwnSymbol)
{
  std::vector<df::UserMarkFootprint> symbols{{1, {0, 0, 20, 20}}};
  std::vector<df::UserMarkFootprint> badges{{2, {15, 10, 50, 30}}, {1, {10, 10, 40, 30}}};
  std::vector<kml::MarkId> visible;
  df::PlaceUserMarkBadges(symbols, badges, visible);
  TEST_EQUAL(visible, (std::vector<kml::MarkId>{1}), ());
  badges = {{2, {45, 10, 75, 30}}, {1, {20, 10, 50, 30}}};
  df::PlaceUserMarkBadges(symbols, badges, visible);
  TEST_EQUAL(visible, (std::vector<kml::MarkId>{1}), ("Overlapping badges have deterministic priority"));
}

UNIT_TEST(UserMarkBadges_LargeDenseLayout)
{
  std::mt19937 random(1749);
  std::uniform_real_distribution<double> x(0, 2880), y(0, 1620), size(20, 80);
  std::vector<df::UserMarkFootprint> symbols, badges;
  for (kml::MarkId id = 1; id <= 3000; ++id)
  {
    double const left = x(random), top = y(random), width = size(random);
    symbols.push_back({id, {left, top, left + width, top + width}});
    badges.push_back({id, {left + width, top, left + width * 2.3, top + width}});
  }
  std::shuffle(badges.begin(), badges.end(), random);
  std::vector<kml::MarkId> visible;
  df::PlaceUserMarkBadges(symbols, badges, visible);
  for (auto const & badge : badges)
  {
    if (!std::binary_search(visible.begin(), visible.end(), badge.m_id))
      continue;
    for (auto const & symbol : symbols)
      TEST(symbol.m_id == badge.m_id || !symbol.m_rect.IsIntersect(badge.m_rect), (badge.m_id, symbol.m_id));
    for (auto const & other : badges)
      if (other.m_id != badge.m_id && std::binary_search(visible.begin(), visible.end(), other.m_id))
        TEST(!other.m_rect.IsIntersect(badge.m_rect), (badge.m_id, other.m_id));
  }
  auto const original = visible;
  std::shuffle(badges.begin(), badges.end(), random);
  df::PlaceUserMarkBadges(symbols, badges, visible);
  TEST_EQUAL(visible, original, ("Tile arrival order must not change badge selection"));
}
#endif
