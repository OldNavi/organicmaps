#include "Framework.hpp"
#include "app/organicmaps/sdk/core/jni_helper.hpp"
#include "geometry/mercator.hpp"
#include "routing/road_info.hpp"

extern "C"
{
JNIEXPORT jobject Java_app_organicmaps_sdk_cluster_RoadInfo_nativeRead(JNIEnv * env, jclass, jdouble latitude,
                                                                       jdouble longitude, jdouble accuracy,
                                                                       jdouble speed, jdouble bearing,
                                                                       jdouble timestamp)
{
  // This JNI entry point is used exclusively by the road-info worker.
  static thread_local auto reader = frm()->GetRoutingManager().CreateRoadInfoReader();
  location::GpsInfo location;
  location.m_latitude = latitude;
  location.m_longitude = longitude;
  location.m_horizontalAccuracy = accuracy;
  location.m_speed = speed;
  location.m_bearing = bearing;
  location.m_timestamp = timestamp;
  routing::RoadInfoSnapshot info;
  try
  {
    info = reader->Read(location);
  }
  catch (routing::RoutingException const &)
  {
    // A downloaded map may be replaced or removed between matching and reading its attributes.
  }
  auto camera = env->NewDoubleArray(info.m_cameraDistance >= 0.0 ? 5 : 0);
  if (info.m_cameraDistance >= 0.0)
  {
    auto const ll = mercator::ToLatLon(info.m_cameraPosition);
    double const values[] = {info.m_cameraDistance, info.m_cameraLimitMps, ll.m_lat, ll.m_lon,
                             info.m_cameraLimitMps > 0 && speed > info.m_cameraLimitMps ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(camera, 0, 5, values);
  }
  auto cls = env->FindClass("app/organicmaps/sdk/cluster/RoadInfo");
  auto constructor = env->GetMethodID(
      cls, "<init>", "(ZDLjava/lang/String;[DDLjava/lang/String;[D[Lapp/organicmaps/sdk/road/RoadEventAhead;)V");
  auto event = env->NewDoubleArray(info.m_eventDistance >= 0 ? 5 : 0);
  if (info.m_eventDistance >= 0)
  {
    auto const ll = mercator::ToLatLon(info.m_event.m_position);
    double const values[] = {static_cast<double>(info.m_event.m_kind), info.m_eventDistance,
                             info.m_event.m_speedKmh / 3.6, ll.m_lat, ll.m_lon};
    env->SetDoubleArrayRegion(event, 0, 5, values);
  }
  static jclass const warningClass = jni::GetGlobalClassRef(env, "app/organicmaps/sdk/road/RoadEventAhead");
  static jmethodID const warningCtor = env->GetMethodID(warningClass, "<init>", "(Ljava/lang/String;IDDDD)V");
  auto warnings = env->NewObjectArray(info.m_warnings.size(), warningClass, nullptr);
  for (size_t i = 0; i < info.m_warnings.size(); ++i)
  {
    auto const & warning = info.m_warnings[i];
    auto const ll = mercator::ToLatLon(warning.m_event.m_position);
    jni::TScopedLocalRef identity(env, jni::ToJavaString(env, warning.m_event.m_sourceId));
    jni::TScopedLocalRef object(
        env, env->NewObject(warningClass, warningCtor, identity.get(), static_cast<jint>(warning.m_event.m_kind),
                            warning.m_distance, warning.m_event.m_speedKmh / 3.6, ll.m_lat, ll.m_lon));
    env->SetObjectArrayElement(warnings, i, object.get());
  }
  return env->NewObject(cls, constructor, static_cast<jboolean>(info.m_matched), info.m_speedLimitMps,
                        jni::ToJavaString(env, info.m_road), camera, info.m_externalSpeedLimitMps,
                        jni::ToJavaString(env, info.m_event.m_sourceId), event, warnings);
}

}  // extern "C"
