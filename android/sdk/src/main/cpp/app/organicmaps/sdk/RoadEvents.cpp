#include "Framework.hpp"
#include "app/organicmaps/sdk/core/jni_helper.hpp"
#include "geometry/mercator.hpp"
#include "routing/road_events.hpp"
#include "routing/speed_camera_manager.hpp"
#include "storage/country_info_getter.hpp"

namespace
{
thread_local std::shared_ptr<routing::RoadEventStore> g_pendingRoadEvents;
constexpr size_t kRoadEventColumns = 9;
}  // namespace

extern "C"
{
JNIEXPORT jlongArray Java_app_organicmaps_sdk_road_RoadEvents_nativeGetState(JNIEnv * env, jclass)
{
  auto const state = frm()->GetRoutingManager().GetRoadEvents()->Get();
  jlong const values[] = {state.m_enabled ? 1 : 0, static_cast<jlong>(state.m_store ? state.m_store->Size() : 0),
                          static_cast<jlong>(state.m_revision)};
  auto result = env->NewLongArray(3);
  env->SetLongArrayRegion(result, 0, 3, values);
  return result;
}

JNIEXPORT void Java_app_organicmaps_sdk_road_RoadEvents_nativeBeginIndex(JNIEnv *, jclass)
{
  g_pendingRoadEvents = std::make_shared<routing::RoadEventStore>();
}
JNIEXPORT void Java_app_organicmaps_sdk_road_RoadEvents_nativeAppendIndex(JNIEnv * env, jclass, jdoubleArray numbers,
                                                                          jobjectArray identities, jlong importedAt)
{
  CHECK(g_pendingRoadEvents, ());
  size_t const count = env->GetArrayLength(identities);
  CHECK_EQUAL(env->GetArrayLength(numbers), count * kRoadEventColumns, ());
  std::vector<double> values(count * kRoadEventColumns);
  env->GetDoubleArrayRegion(numbers, 0, values.size(), values.data());
  for (size_t i = 0; i < count; ++i)
  {
    auto const * v = values.data() + i * kRoadEventColumns;
    routing::RoadEvent e;
    e.m_importedAt = static_cast<uint64_t>(importedAt);
    e.m_position = mercator::FromLatLon(v[0], v[1]);
    e.m_kind = static_cast<routing::RoadEventKind>(v[3]);
    e.m_speedKmh = static_cast<uint16_t>(v[4]);
    e.m_distance = static_cast<uint16_t>(v[5]);
    e.m_direction = static_cast<uint16_t>(v[6]);
    e.m_directionType = static_cast<uint8_t>(v[7]);
    e.m_angle = static_cast<uint8_t>(v[8]);
    auto id = static_cast<jstring>(env->GetObjectArrayElement(identities, i));
    e.m_sourceId = jni::ToNativeString(env, id);
    env->DeleteLocalRef(id);
    g_pendingRoadEvents->Add(std::move(e));
  }
}
JNIEXPORT void Java_app_organicmaps_sdk_road_RoadEvents_nativePublishIndex(JNIEnv *, jclass)
{
  CHECK(g_pendingRoadEvents, ());
  frm()->GetRoutingManager().GetRoadEvents()->Replace(std::move(g_pendingRoadEvents));
}
JNIEXPORT void Java_app_organicmaps_sdk_road_RoadEvents_nativeDiscardPrepared(JNIEnv *, jclass)
{
  g_pendingRoadEvents.reset();
}
JNIEXPORT jobjectArray Java_app_organicmaps_sdk_road_RoadEvents_nativeCountriesNear(JNIEnv * env, jclass, jdouble lat,
                                                                                    jdouble lon)
{
  auto const position = mercator::FromLatLon(lat, lon);
  auto const & getter = frm()->GetCountryInfoGetter();
  std::vector<std::string> regions;
  getter.GetRegionsCountryId(position, regions, 30000);
  auto const current = getter.GetRegionCountryId(position);
  // Keep an empty first entry outside known countries: nearby regions must not become the current country.
  regions.insert(regions.begin(), current);
  std::vector<std::string> countries;
  for (auto const & region : regions)
    if (std::find(countries.begin(), countries.end(), region) == countries.end())
      countries.push_back(region);
  auto result = env->NewObjectArray(countries.size(), env->FindClass("java/lang/String"), nullptr);
  for (size_t i = 0; i < countries.size(); ++i)
  {
    auto country = jni::ToJavaString(env, countries[i]);
    env->SetObjectArrayElement(result, i, country);
    env->DeleteLocalRef(country);
  }
  return result;
}

JNIEXPORT void Java_app_organicmaps_sdk_road_RoadEvents_nativeConfigure(JNIEnv * env, jclass, jboolean enabled,
                                                                        jboolean warnings, jint visibleKinds,
                                                                        jintArray minZooms)
{
  routing::RoadEventMinZooms zooms;
  CHECK_EQUAL(static_cast<size_t>(env->GetArrayLength(minZooms)), zooms.size(), ());
  env->GetIntArrayRegion(minZooms, 0, zooms.size(), zooms.data());
  frm()->GetRoutingManager().GetRoadEvents()->Configure(enabled, warnings, visibleKinds, zooms);
  frm()->GetRoutingManager().GetSpeedCamManager().SetExternalNotifications(true);
  frm()->GetRoutingManager().GetSpeedCamManager().SetCameraVisible(
      (visibleKinds & (1u << static_cast<unsigned>(routing::RoadEventKind::Camera))) != 0);
  if (auto engine = frm()->GetDrapeEngine())
    engine->RefreshExternalMarks();
  frm()->GetRoutingManager().GetNavigationScene().RefreshExternalMarks();
}

}  // extern "C"
