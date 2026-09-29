#include "testing/testing.hpp"

#include "generator/feature_builder.hpp"
#include "generator/generator_tests_support/test_mwm_builder.hpp"
#include "generator/generator_tests_support/test_with_classificator.hpp"
#include "generator/osm_element.hpp"
#include "generator/road_details_parser.hpp"
#include "generator/road_junctions_builder.hpp"

#include "platform/local_country_file.hpp"
#include "platform/platform_tests_support/scoped_dir.hpp"
#include "platform/platform_tests_support/scoped_file.hpp"

#include "indexer/data_source.hpp"

#include "geometry/mercator.hpp"

#include "coding/files_container.hpp"

#include "defines.hpp"

#include <thread>

namespace road_details_tests
{
using feature::RoadDetails;
using generator::ParseRoadDetails;
using generator::tests_support::TestWithClassificator;

OsmElement Way(std::initializer_list<OsmElement::Tag> tags)
{
  OsmElement element;
  element.m_type = OsmElement::EntityType::Way;
  element.AddTag("highway", "trunk");
  for (auto const & tag : tags)
    element.UpdateTag(tag.m_key, tag.m_value);
  return element;
}

UNIT_TEST(RoadDetails_ExplicitFiveLanes)
{
  auto const details =
      ParseRoadDetails(Way({{"lanes", "5"}, {"oneway", "yes"}, {"width:lanes", "3.5|3.5|3.75|3.75|3.75"}}));
  TEST(details, ());
  TEST(details->m_oneWay, ());
  TEST_EQUAL(details->m_lanes.size(), 5, ());
  TEST_ALMOST_EQUAL_ABS(details->WidthMeters(), 18.25, 1e-9, ());
  TEST_EQUAL(details->m_lanes[0].m_widthCm, 350, ());
  TEST_EQUAL(details->m_lanes[4].m_widthCm, 375, ());
  for (auto const & lane : details->m_lanes)
    TEST(lane.m_source == RoadDetails::WidthSource::Explicit, ());
}

UNIT_TEST(RoadDetails_PartialWidthsAndTotal)
{
  auto const road = Way({{"lanes", "3"}, {"width:lanes", "3.5||3.75"}, {"width", "11 m"}});
  auto const details = ParseRoadDetails(road);
  TEST(details, ());
  TEST_EQUAL(details->m_lanes[0].m_widthCm, 350, ());
  TEST_EQUAL(details->m_lanes[1].m_widthCm, 375, ());
  TEST(details->m_lanes[1].m_source == RoadDetails::WidthSource::TotalWidth, ());
  TEST_EQUAL(details->m_lanes[2].m_widthCm, 375, ());
  auto shoulder = road;
  shoulder.AddTag("shoulder", "yes");
  auto const fallback = ParseRoadDetails(shoulder);
  TEST_EQUAL(fallback->m_lanes[1].m_widthCm, 350, ());
  TEST(fallback->m_lanes[1].m_source == RoadDetails::WidthSource::Default, ());
}

UNIT_TEST(RoadDetails_InvalidAndMissingTags)
{
  for (auto const * count : {"0", "-1", "33", "2;3", "2.5"})
    TEST(!ParseRoadDetails(Way({{"lanes", count}})), (count));
  TEST(!ParseRoadDetails(Way({{"lanes", "2"}, {"highway", "footway"}})), ());
  TEST(!ParseRoadDetails(Way({{"lanes", "2"}, {"placement", "right_of:1"}})), ());
  auto const details = ParseRoadDetails(Way({{"lanes", "3"}, {"width:lanes", "-3|nan|4"}, {"lane_markings", "no"}}));
  TEST(details, ());
  TEST(!details->m_markings, ());
  TEST_EQUAL(details->m_lanes[0].m_widthCm, 350, ());
  TEST_EQUAL(details->m_lanes[1].m_widthCm, 350, ());
  TEST_EQUAL(details->m_lanes[2].m_widthCm, 400, ());
  auto const mismatched = ParseRoadDetails(Way({{"lanes", "2"}, {"width:lanes", "3|4|5"}}));
  TEST_ALMOST_EQUAL_ABS(mismatched->WidthMeters(), 7.0, 1e-9, ());
}

UNIT_TEST(RoadDetails_InferredRoadWidthsWithoutInventedMarkings)
{
  for (auto const * highway : {"primary", "secondary", "tertiary", "residential", "unclassified"})
  {
    auto const details = ParseRoadDetails(Way({{"highway", highway}}));
    TEST(details, (highway));
    TEST_ALMOST_EQUAL_ABS(details->WidthMeters(), 7.0, 1e-9, (highway));
    TEST(!details->m_markings, (highway));
  }
  auto const service = ParseRoadDetails(Way({{"highway", "service"}}));
  TEST_ALMOST_EQUAL_ABS(service->WidthMeters(), 3.5, 1e-9, ());
  auto const oneWay = ParseRoadDetails(Way({{"highway", "residential"}, {"oneway", "yes"}}));
  TEST_ALMOST_EQUAL_ABS(oneWay->WidthMeters(), 3.5, 1e-9, ());
  auto const wide = ParseRoadDetails(Way({{"width", "9"}}));
  TEST_ALMOST_EQUAL_ABS(wide->WidthMeters(), 9.0, 1e-9, ());
}

UNIT_TEST(RoadDetails_ReverseAndDrivingSide)
{
  auto reverse = ParseRoadDetails(Way({{"lanes", "2"}, {"oneway", "-1"}, {"width:lanes", "3|4"}}));
  TEST_EQUAL(reverse->m_lanes[0].m_widthCm, 300, ("Widths are left to right in travel direction"));
  auto directional = ParseRoadDetails(Way({{"lanes:forward", "2"},
                                           {"lanes:backward", "2"},
                                           {"width:lanes:forward", "3|3.5"},
                                           {"width:lanes:backward", "4|4.5"}}));
  TEST(directional, ());
  TEST_EQUAL(directional->m_lanes[0].m_widthCm, 450, ());
  TEST_EQUAL(directional->m_lanes[1].m_widthCm, 400, ());
  TEST_EQUAL(directional->m_lanes[2].m_widthCm, 300, ());
  directional->ArrangeForDrivingSide(true);
  TEST_EQUAL(directional->m_lanes[0].m_widthCm, 300, ());
  TEST_EQUAL(directional->m_lanes[1].m_widthCm, 350, ());
  TEST_EQUAL(directional->m_lanes[2].m_widthCm, 450, ());
  auto const once = *directional;
  directional->ArrangeForDrivingSide(true);
  TEST(*directional == once, ());
}

UNIT_CLASS_TEST(TestWithClassificator, RoadDetails_IntermediateRoundTripAndLegacy)
{
  feature::FeatureBuilder original;
  original.SetType(classif().GetTypeByPath({"highway", "trunk"}));
  original.AssignPoints({{37, 55}, {37.001, 55.001}});
  original.SetLinear();
  original.SetRoadDetails(ParseRoadDetails(Way({{"lanes", "3"}, {"width:lanes", "3.5||4"}})));
  original.SetRoadNodeIds({123, 456});
  feature::FeatureBuilder decoded;
  for (bool accurate : {false, true})
  {
    feature::FeatureBuilder::Buffer bytes;
    if (accurate)
    {
      original.SerializeAccuratelyForIntermediate(bytes);
      decoded.DeserializeAccuratelyFromIntermediate(bytes);
    }
    else
    {
      original.SerializeForIntermediate(bytes);
      decoded.DeserializeFromIntermediate(bytes);
    }
    TEST(decoded.GetRoadDetails() == original.GetRoadDetails(), ());
    TEST_EQUAL(decoded.GetRoadNodeIds(), original.GetRoadNodeIds(), ());
    auto legacy = original;
    legacy.SetRoadDetails({});
    if (accurate)
    {
      legacy.SerializeAccuratelyForIntermediate(bytes);
      decoded.DeserializeAccuratelyFromIntermediate(bytes);
    }
    else
    {
      legacy.SerializeForIntermediate(bytes);
      decoded.DeserializeFromIntermediate(bytes);
    }
    TEST(!decoded.GetRoadDetails(), ("Reusing a reader must clear the previous extension"));
  }
}

UNIT_TEST(RoadDetails_OptionalSectionConcurrentReads)
{
  platform::tests_support::ScopedFile file("road-details-section.mwm",
                                           platform::tests_support::ScopedFile::Mode::Create);
  auto const details = *ParseRoadDetails(Way({{"lanes", "5"}, {"width:lanes", "3.5|3.5|3.75|3.75|3.75"}}));
  {
    FilesContainerW container(file.GetFullPath());
    auto writer = container.GetWriter("empty");
    WriteToSink(*writer, uint8_t{0});
  }
  TEST(!feature::RoadDetailsReader::Load(FilesContainerR(file.GetFullPath())), ());
  {
    FilesContainerW container(file.GetFullPath(), FileWriter::OP_WRITE_EXISTING);
    auto writer = container.GetWriter(ROAD_DETAILS_FILE_TAG);
    feature::RoadDetailsBuilder builder;
    for (uint32_t i = 0; i < 100; ++i)
      builder.Put(i * 3, details);
    builder.Freeze(*writer);
  }
  auto const reader = feature::RoadDetailsReader::Load(FilesContainerR(file.GetFullPath()));
  TEST(reader, ());
  TEST_EQUAL(reader->Count(), 100, ());
  auto const check = [&]
  {
    for (uint32_t i = 0; i < 100; ++i)
    {
      auto const value = reader->Get(i * 3);
      TEST(value && *value == details, (i));
      TEST(!reader->Get(i * 3 + 1), (i));
    }
  };
  std::thread other(check);
  check();
  other.join();
}

UNIT_CLASS_TEST(TestWithClassificator, RoadDetails_MwmRoundTripAndPhysicalFootprint)
{
  auto const directory = GetPlatform().WritableDir() + "road-details-mwm-test";
  platform::tests_support::ScopedDirCleanup cleanup(directory);
  platform::LocalCountryFile country(directory, platform::CountryFile("RoadDetailTest"), 0);
  auto const origin = mercator::FromLatLon(55.62, 37.79);
  auto const unit = 0.00001 / mercator::DistanceOnEarth(origin, origin + m2::PointD(0.00001, 0));
  auto const details =
      *ParseRoadDetails(Way({{"lanes", "5"}, {"oneway", "yes"}, {"width:lanes", "3.5|3.5|3.75|3.75|3.75"}}));
  {
    generator::tests_support::TestMwmBuilder builder(country, feature::DataHeader::MapType::Country);
    feature::FeatureBuilder road;
    road.SetType(classif().GetTypeByPath({"highway", "trunk"}));
    road.AssignPoints({origin, origin + m2::PointD(100 * unit, 0)});
    road.SetLinear();
    road.SetRoadDetails(details);
    TEST(builder.Add(road), ());
  }
  FrozenDataSource source;
  auto const [id, result] = source.RegisterMap(country);
  TEST_EQUAL(result, MwmSet::RegResult::Success, ());
  size_t found = 0;
  auto const point = origin + m2::PointD(50 * unit, 8 * unit);
  source.ForEachInRect([&](FeatureType & feature)
  {
    auto const decoded = feature.GetRoadDetails();
    TEST(decoded && *decoded == details, ());
    ++found;
  }, m2::RectD(point.x - unit, point.y - unit, point.x + unit, point.y + unit), 17);
  TEST_EQUAL(found, 1, ("The carriageway edge must be indexed even outside the road reference line"));
}
UNIT_TEST(RoadDetails_TurnMasks)
{
  auto const details =
      ParseRoadDetails(Way({{"lanes", "3"}, {"oneway", "yes"}, {"turn:lanes", "through|through|right"}}));
  TEST_EQUAL(details->m_lanes[0].m_turns, RoadDetails::Through, ());
  TEST_EQUAL(details->m_lanes[1].m_turns, RoadDetails::Through, ());
  TEST_EQUAL(details->m_lanes[2].m_turns, RoadDetails::Right, ());
  auto const reverse =
      ParseRoadDetails(Way({{"lanes", "2"}, {"oneway", "-1"}, {"turn:lanes:backward", "left;through|merge_to_right"}}));
  TEST_EQUAL(reverse->m_lanes[0].m_turns, RoadDetails::Left | RoadDetails::Through, ());
  TEST_EQUAL(reverse->m_lanes[1].m_turns, RoadDetails::MergeRight, ());
}

feature::FeatureBuilder Road(std::vector<m2::PointD> points, std::vector<uint64_t> nodes, size_t lanes)
{
  feature::FeatureBuilder road;
  road.SetType(classif().GetTypeByPath({"highway", "primary"}));
  road.AssignPoints(std::move(points));
  road.SetLinear();
  RoadDetails details;
  details.m_oneWay = true;
  details.m_lanes.resize(lanes);
  road.SetRoadDetails(details);
  road.SetRoadNodeIds(std::move(nodes));
  return road;
}

UNIT_CLASS_TEST(TestWithClassificator, RoadJunctions_MergeAndSharedNodeIdentity)
{
  generator::RoadJunctionsBuilder builder;
  auto const p = mercator::FromLatLon(55, 37);
  builder.Add(10, Road({p + m2::PointD(-0.001, 0.0002), p}, {1, 3}, 1));
  builder.Add(11, Road({p + m2::PointD(-0.001, -0.0002), p}, {2, 3}, 1));
  builder.Add(12, Road({p, p + m2::PointD(0.001, 0)}, {3, 4}, 2));
  // Same coordinates on an unrelated road are not connectivity.
  builder.Add(13, Road({p + m2::PointD(0, -0.001), p}, {5, 99}, 1));
  auto const junctions = builder.Build();
  TEST_EQUAL(junctions.size(), 1, ());
  TEST_EQUAL(junctions.front().m_ownerFeatureId, 12, ());
  TEST_EQUAL(junctions.front().m_arms.size(), 3, ());
  size_t incoming = 0, outgoing = 0;
  for (auto const & arm : junctions.front().m_arms)
    (arm.m_forward ? outgoing : incoming) += arm.m_widthsCm.size();
  TEST_EQUAL(incoming, 2, ());
  TEST_EQUAL(outgoing, 2, ());

  platform::tests_support::ScopedFile file("road-junctions.mwm", platform::tests_support::ScopedFile::Mode::Create);
  {
    FilesContainerW container(file.GetFullPath());
    auto writer = container.GetWriter(ROAD_JUNCTIONS_FILE_TAG);
    feature::RoadJunctions::Write(*writer, junctions);
  }
  auto const reader = feature::RoadJunctions::Load(FilesContainerR(file.GetFullPath()));
  TEST_EQUAL(reader->Get(10).size(), 1, ());
  TEST(reader->Get(13).empty(), ());
  TEST(!reader->Get(10).front().Arm().m_forward, ());
  TEST(reader->Get(12).front().Arm().m_forward, ());
  TEST_ALMOST_EQUAL_ABS(reader->Get(12).front().Arm().WidthMeters(), 7.0, 1e-9, ());
}

UNIT_CLASS_TEST(TestWithClassificator, RoadJunctions_RoundaboutInternalNode)
{
  auto const p = mercator::FromLatLon(55, 37);
  std::vector<m2::PointD> const ring = {p, p + m2::PointD(0.001, 0), p + m2::PointD(0.001, 0.001),
                                        p + m2::PointD(0, 0.001), p};
  generator::RoadJunctionsBuilder builder;
  builder.Add(1, Road(ring, {10, 11, 12, 13, 10}, 2));
  builder.Add(2, Road({ring[2] + m2::PointD(0.001, 0.001), ring[2]}, {20, 12}, 1));
  auto const junctions = builder.Build();
  TEST_EQUAL(junctions.size(), 1, ());
  TEST_EQUAL(junctions.front().m_arms.size(), 3, ());
  size_t ringArms = 0;
  for (auto const & arm : junctions.front().m_arms)
  {
    if (arm.m_featureId != 1)
      continue;
    ++ringArms;
    TEST(!arm.m_featureEndpoint, ());
    TEST_GREATER(arm.m_nodeDistance, 0, ());
    TEST_GREATER(arm.m_cutDistance, 0, ());
  }
  TEST_EQUAL(ringArms, 2, ("A closed way contributes incoming and outgoing arms at its internal junction node"));
  TEST(junctions.front().HasContinuation(), ());
}
UNIT_TEST(RoadDetails_LegacySectionWithoutTurnMasks)
{
  platform::tests_support::ScopedFile file("road-details-legacy.mwm",
                                           platform::tests_support::ScopedFile::Mode::Create);
  auto const details = *ParseRoadDetails(Way({{"lanes", "2"}, {"oneway", "yes"}, {"width:lanes", "3.5|4"}}));
  {
    FilesContainerW container(file.GetFullPath());
    auto writer = container.GetWriter("empty");
    WriteToSink(*writer, uint8_t{0});
  }
  {
    FilesContainerW container(file.GetFullPath(), FileWriter::OP_WRITE_EXISTING);
    auto writer = container.GetWriter(ROAD_DETAILS_FILE_TAG);
    WriteToSink(*writer, uint64_t{0});
    MapUint32ToValueBuilder<std::vector<uint8_t>> builder;
    // Version 0 record: two one-way lanes, markings, no directional groups, explicit 350/400 cm.
    builder.Put(5, {2, 3, 0, 0, 0xde, 0x02, 0, 0x90, 0x03, 0});
    builder.Freeze(*writer, [](auto & sink, auto begin, auto end)
    {
      for (auto it = begin; it != end; ++it)
        sink.Write(it->data(), it->size());
    }, 8);
  }
  auto const reader = feature::RoadDetailsReader::Load(FilesContainerR(file.GetFullPath()));
  auto const decoded = reader->Get(5);
  TEST(decoded && *decoded == details, ());
  TEST(reader->GetJunctions(5).empty(), ());
}
UNIT_CLASS_TEST(TestWithClassificator, RoadDetails_SharedAcrossActiveMwmHandles)
{
  auto const directory = GetPlatform().WritableDir() + "road_details_shared_handles";
  platform::tests_support::ScopedDirCleanup cleanup(directory);
  platform::LocalCountryFile country(directory, platform::CountryFile("RoadDetailShared"), 0);
  {
    generator::tests_support::TestMwmBuilder builder(country, feature::DataHeader::MapType::Country);
    feature::FeatureBuilder road;
    road.SetType(classif().GetTypeByPath({"highway", "trunk"}));
    road.SetLinear();
    road.AssignPoints({mercator::FromLatLon(55.62, 37.79), mercator::FromLatLon(55.63, 37.80)});
    road.SetRoadDetails(ParseRoadDetails(Way({{"lanes", "5"}, {"oneway", "yes"}})));
    TEST(builder.Add(road), ());
  }
  FrozenDataSource source;
  auto const [id, result] = source.RegisterMap(country);
  TEST_EQUAL(result, MwmSet::RegResult::Success, ());
  std::weak_ptr<feature::RoadDetailsReader const> reader;
  {
    auto first = source.GetMwmHandleById(id);
    auto second = source.GetMwmHandleById(id);
    TEST(first.IsAlive() && second.IsAlive(), ());
    TEST(first.GetValue() != second.GetValue(), ("Concurrent readers have separate MwmValues"));
    TEST(first.GetValue()->m_roadDetails, ());
    TEST_EQUAL(first.GetValue()->m_roadDetails.get(), second.GetValue()->m_roadDetails.get(),
               ("Immutable road sections must not be duplicated per reader"));
    reader = first.GetValue()->m_roadDetails;
  }
  source.ClearCache();
  TEST(reader.expired(), ("An idle MwmInfo must not retain the road data after cache eviction"));
}
}  // namespace road_details_tests

namespace road_details_tests
{
UNIT_CLASS_TEST(TestWithClassificator, RoadDetails_InferOnlyUnmappedWidthBetweenRoadContinuations)
{
  auto const p = mercator::FromLatLon(55.6571, 37.838);
  auto const unit = 0.00001 / mercator::DistanceOnEarth(p, p + m2::PointD(0.00001, 0));
  generator::RoadJunctionsBuilder builder;
  auto missing = Road({p, p + m2::PointD(14 * unit, 0)}, {2, 3}, 1);
  auto details = *ParseRoadDetails(Way({{"oneway", "yes"}}));
  TEST(details.m_inferredLaneCount, ());
  missing.SetRoadDetails(details);
  builder.Add(1, Road({p - m2::PointD(100 * unit, 0), p}, {1, 2}, 3));
  builder.Add(2, missing);
  builder.Add(3, Road({p + m2::PointD(14 * unit, 0), p + m2::PointD(100 * unit, 0)}, {3, 4}, 3));
  auto ramp = Road({p, p + m2::PointD(100 * unit, 20 * unit)}, {2, 5}, 1);
  ramp.SetType(classif().GetTypeByPath({"highway", "primary_link"}));
  builder.Add(4, ramp);
  auto const junctions = builder.Build();
  TEST_EQUAL(junctions.size(), 2, ());
  builder.ForEachRoad([&](uint32_t id, RoadDetails const & d)
  {
    if (id != 2)
      return;
    TEST_ALMOST_EQUAL_ABS(d.WidthMeters(), 10.5, 1e-9, ());
    TEST(!d.m_markings, ("Estimated width does not supply surveyed lane paint"));
    TEST(d.m_inferredLaneCount, ());
  });
  for (auto const & j : junctions)
  {
    TEST(j.HasContinuation(), ());
    for (auto const index : {j.m_continuationA, j.m_continuationB})
      TEST_NOT_EQUAL(j.m_arms[index].m_featureId, 4, ("A ramp cannot become the through road"));
  }

  details.m_inferredLaneCount = false;  // Explicit one-lane road with lane_markings=no.
  details.m_lanes.resize(1);
  missing.SetRoadDetails(details);
  generator::RoadJunctionsBuilder explicitBuilder;
  explicitBuilder.Add(1, Road({p - m2::PointD(100 * unit, 0), p}, {1, 2}, 3));
  explicitBuilder.Add(2, missing);
  explicitBuilder.Add(3, Road({p + m2::PointD(14 * unit, 0), p + m2::PointD(100 * unit, 0)}, {3, 4}, 3));
  explicitBuilder.Build();
  explicitBuilder.ForEachRoad([&](uint32_t id, RoadDetails const & d)
  {
    if (id == 2)
      TEST_ALMOST_EQUAL_ABS(d.WidthMeters(), 3.5, 1e-9, ("Explicit lane count must not be inferred"));
  });
}

UNIT_CLASS_TEST(TestWithClassificator, RoadJunctions_WidthChangeMustNotSelectEqualWidthSideRoad)
{
  auto const p = mercator::FromLatLon(55.6571, 37.838);
  generator::RoadJunctionsBuilder builder;
  builder.Add(1, Road({p + m2::PointD(0.001, 0), p}, {1, 2}, 1));
  builder.Add(2, Road({p, p - m2::PointD(0.001, 0)}, {2, 3}, 3));
  builder.Add(3, Road({p, p + m2::PointD(-0.001, 0.0002)}, {2, 4}, 1));
  auto const junctions = builder.Build();
  TEST_EQUAL(junctions.size(), 1, ());
  TEST(!junctions[0].HasContinuation(), ("The main-road width change requires a transition mesh"));
}
}  // namespace road_details_tests

namespace road_details_tests
{
UNIT_CLASS_TEST(TestWithClassificator, RoadJunctions_WiderBranchNeedsTransitionOnNarrowThroughRoad)
{
  auto const p = mercator::FromLatLon(55.6188287, 37.7800626);
  generator::RoadJunctionsBuilder builder;
  builder.Add(1, Road({p + m2::PointD(0.001, -0.0005), p, p + m2::PointD(-0.001, 0.0003)}, {1, 2, 3}, 1));
  builder.Add(2, Road({p + m2::PointD(0.001, -0.0001), p}, {4, 2}, 2));
  auto const junctions = builder.Build();
  TEST_EQUAL(junctions.size(), 1, ());
  TEST(!junctions[0].HasContinuation(), ("A wider joining ribbon cannot terminate on the narrow road centerline"));
}
}  // namespace road_details_tests

namespace road_details_tests
{
UNIT_CLASS_TEST(TestWithClassificator, RoadJunctions_WiderApproachKeepsRoundaboutContour)
{
  auto const p = mercator::FromLatLon(55.62, 37.78);
  auto ring = Road({p, p + m2::PointD(0.001, 0), p + m2::PointD(0.001, 0.001), p + m2::PointD(0, 0.001), p},
                   {1, 2, 3, 4, 1}, 1);
  auto details = *ring.GetRoadDetails();
  details.m_roundabout = true;
  ring.SetRoadDetails(details);
  generator::RoadJunctionsBuilder builder;
  builder.Add(1, ring);
  builder.Add(2, Road({p + m2::PointD(0.002, 0.002), p + m2::PointD(0.001, 0.001)}, {5, 3}, 2));
  auto const junctions = builder.Build();
  TEST_EQUAL(junctions.size(), 1, ());
  TEST(junctions[0].HasContinuation(), ("A wide approach must not replace the ring's original contour"));
}
}  // namespace road_details_tests

namespace road_details_tests
{
UNIT_TEST(RoadDetails_PlacementOffsetsAndDirection)
{
  auto const fixed = ParseRoadDetails(Way({{"oneway", "yes"}, {"lanes", "3"}, {"placement", "right_of:1"}}));
  TEST(fixed, ());
  TEST_EQUAL(fixed->m_startOffsetCm, -175, ());
  TEST_EQUAL(fixed->m_endOffsetCm, -175, ());
  auto const variable = ParseRoadDetails(
      Way({{"oneway", "yes"}, {"lanes", "3"}, {"placement:start", "middle_of:2"}, {"placement:end", "right_of:2"}}));
  TEST(variable, ());
  TEST_EQUAL(variable->m_startOffsetCm, 0, ());
  TEST_EQUAL(variable->m_endOffsetCm, 175, ());
  auto const reverse = ParseRoadDetails(
      Way({{"oneway", "-1"}, {"lanes", "3"}, {"placement:start", "middle_of:2"}, {"placement:end", "right_of:2"}}));
  TEST(reverse, ());
  TEST_EQUAL(reverse->m_startOffsetCm, -175, ());
  TEST_EQUAL(reverse->m_endOffsetCm, 0, ());
  for (auto value : {"right_of:0", "middle_of:4", "unknown:1"})
    TEST(!ParseRoadDetails(Way({{"oneway", "yes"}, {"lanes", "3"}, {"placement", value}})), (value));

  platform::tests_support::ScopedFile file("road-placement.mwm", platform::tests_support::ScopedFile::Mode::Create);
  {
    FilesContainerW container(file.GetFullPath());
    auto writer = container.GetWriter("empty");
    WriteToSink(*writer, uint8_t{0});
  }
  {
    FilesContainerW container(file.GetFullPath(), FileWriter::OP_WRITE_EXISTING);
    auto writer = container.GetWriter(ROAD_DETAILS_FILE_TAG);
    feature::RoadDetailsBuilder builder;
    for (uint32_t i = 0; i < 20; ++i)
      builder.Put(i, i % 2 ? *fixed : *variable);
    builder.Freeze(*writer);
  }
  auto const reader = feature::RoadDetailsReader::Load(FilesContainerR(file.GetFullPath()));
  for (uint32_t i = 0; i < 20; ++i)
    TEST(reader->Get(i) == (i % 2 ? *fixed : *variable), (i));
}

UNIT_CLASS_TEST(TestWithClassificator, RoadJunctions_PlacementTransitionUsesNeighborFrames)
{
  auto const p = mercator::FromLatLon(55.64, 37.82);
  auto const unit = 0.00001 / mercator::DistanceOnEarth(p, p + m2::PointD(0.00001, 0));
  auto before = Road({p - m2::PointD(50 * unit, 0), p}, {1, 2}, 3);
  auto fixed = *before.GetRoadDetails();
  fixed.m_startOffsetCm = fixed.m_endOffsetCm = -175;
  before.SetRoadDetails(fixed);
  auto transition = Road({p, p + m2::PointD(50 * unit, 0)}, {2, 3}, 3);
  auto details = ParseRoadDetails(Way({{"oneway", "yes"}, {"lanes", "3"}, {"placement", "transition"}}));
  TEST(details && details->m_placementTransition && !details->m_markings, ());
  transition.SetRoadDetails(details);
  generator::RoadJunctionsBuilder builder;
  builder.Add(1, before);
  builder.Add(2, transition);
  builder.Add(3, Road({p + m2::PointD(50 * unit, 0), p + m2::PointD(100 * unit, 0)}, {3, 4}, 3));
  TEST_EQUAL(builder.Build().size(), 2, ());
  builder.ForEachRoad([&](uint32_t id, RoadDetails const & d)
  {
    if (id == 2)
    {
      TEST_EQUAL(d.m_startOffsetCm, -175, ());
      TEST_EQUAL(d.m_endOffsetCm, 0, ());
    }
  });
}
}  // namespace road_details_tests

namespace road_details_tests
{
UNIT_TEST(RoadDetails_PublicTransportLaneDesignations)
{
  auto const road = ParseRoadDetails(Way(
      {{"oneway", "yes"}, {"lanes", "3"}, {"psv:lanes", "yes||designated"}, {"turn:lanes", "through|through|right"}}));
  TEST(road, ());
  TEST(!road->m_lanes[0].m_publicTransport && !road->m_lanes[1].m_publicTransport, ());
  TEST(road->m_lanes[2].m_publicTransport, ());
  TEST_EQUAL(road->m_lanes[2].m_turns, RoadDetails::Right, ());
  auto direction = ParseRoadDetails(Way({{"lanes", "4"},
                                         {"lanes:forward", "2"},
                                         {"lanes:backward", "2"},
                                         {"psv:lanes:forward", "designated|yes"},
                                         {"psv:lanes:backward", "designated|yes"}}));
  TEST(direction, ());
  TEST(!direction->m_lanes[0].m_publicTransport && direction->m_lanes[1].m_publicTransport, ());
  TEST(direction->m_lanes[2].m_publicTransport && !direction->m_lanes[3].m_publicTransport, ());
  direction->ArrangeForDrivingSide(true);
  TEST(direction->m_lanes[0].m_publicTransport && !direction->m_lanes[1].m_publicTransport, ());
  auto const reverse =
      ParseRoadDetails(Way({{"oneway", "-1"}, {"lanes", "2"}, {"bus:lanes:backward", "yes|designated"}}));
  TEST(reverse && !reverse->m_lanes[0].m_publicTransport && reverse->m_lanes[1].m_publicTransport, ());
  auto const mismatch = ParseRoadDetails(Way({{"oneway", "yes"}, {"lanes", "3"}, {"psv:lanes", "yes|designated"}}));
  TEST(mismatch, ());
  for (auto const & lane : mismatch->m_lanes)
    TEST(!lane.m_publicTransport, ("Do not guess a lane when array lengths differ"));

  platform::tests_support::ScopedFile file("road-psv.mwm", platform::tests_support::ScopedFile::Mode::Create);
  {
    FilesContainerW container(file.GetFullPath());
    auto writer = container.GetWriter("empty");
    WriteToSink(*writer, uint8_t{0});
  }
  {
    FilesContainerW container(file.GetFullPath(), FileWriter::OP_WRITE_EXISTING);
    auto writer = container.GetWriter(ROAD_DETAILS_FILE_TAG);
    feature::RoadDetailsBuilder builder;
    builder.Put(0, *road);
    builder.Put(1, *direction);
    builder.Freeze(*writer);
  }
  auto reader = feature::RoadDetailsReader::Load(FilesContainerR(file.GetFullPath()));
  TEST(reader->Get(0) == *road && reader->Get(1) == *direction, ());
}
}  // namespace road_details_tests
