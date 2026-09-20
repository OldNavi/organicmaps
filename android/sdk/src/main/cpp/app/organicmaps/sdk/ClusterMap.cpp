#include "Framework.hpp"
#include "app/organicmaps/sdk/core/jni_helper.hpp"
#include "geometry/mercator.hpp"
#include "routing/road_info.hpp"
#include "routing/route.hpp"
#include "routing/speed_camera.hpp"
#include "routing/speed_camera_manager.hpp"

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
  auto constructor = env->GetMethodID(cls, "<init>", "(ZDLjava/lang/String;[D)V");
  return env->NewObject(cls, constructor, static_cast<jboolean>(info.m_matched), info.m_speedLimitMps,
                        jni::ToJavaString(env, info.m_road), camera);
}

JNIEXPORT jlong Java_app_organicmaps_sdk_cluster_ClusterMap_nativeCreate(JNIEnv * env, jclass, jobject surface,
                                                                         jint dpi, jdouble scale, jint zoom,
                                                                         jboolean showPoi, jboolean buildings3d,
                                                                         jdouble tilt, jdouble anchorX, jdouble anchorY)
{
  return g_framework->CreateNavigationView(env, surface, dpi, scale, zoom, showPoi, buildings3d, tilt, anchorX,
                                           anchorY);
}
JNIEXPORT void Java_app_organicmaps_sdk_cluster_ClusterMap_nativeDestroy(JNIEnv *, jclass, jlong handle)
{
  g_framework->DestroyNavigationView(handle);
}
JNIEXPORT void Java_app_organicmaps_sdk_cluster_ClusterMap_nativeSetScale(JNIEnv *, jclass, jlong handle, jint dpi,
                                                                          jdouble scale)
{
  g_framework->SetNavigationViewScale(handle, dpi, scale);
}
JNIEXPORT void Java_app_organicmaps_sdk_cluster_ClusterMap_nativeResize(JNIEnv *, jclass, jlong handle, jint width,
                                                                        jint height)
{
  g_framework->ResizeNavigationView(handle, width, height);
}
JNIEXPORT void Java_app_organicmaps_sdk_cluster_ClusterMap_nativeSetCamera(JNIEnv *, jclass, jlong handle, jint zoom,
                                                                           jdouble tilt, jdouble anchorX,
                                                                           jdouble anchorY)
{
  g_framework->SetNavigationViewCamera(handle, zoom, tilt, anchorX, anchorY);
}
JNIEXPORT jlongArray Java_app_organicmaps_sdk_cluster_ClusterMap_nativeGetTileStats(JNIEnv * env, jclass, jlong handle)
{
  auto const stats = g_framework->GetNavigationViewTileStats(handle);
  jlong const values[] = {stats[0], stats[1], stats[2], stats[3]};
  auto result = env->NewLongArray(4);
  env->SetLongArrayRegion(result, 0, 4, values);
  return result;
}
JNIEXPORT jdouble Java_app_organicmaps_sdk_cluster_ClusterMap_nativeGetCurrentTilt(JNIEnv *, jclass, jlong handle)
{
  return g_framework->GetNavigationViewTilt(handle);
}
JNIEXPORT void Java_app_organicmaps_sdk_cluster_ClusterMap_nativeSet3dBuildings(JNIEnv *, jclass, jlong handle,
                                                                                jboolean enabled)
{
  g_framework->SetNavigationView3dBuildings(handle, enabled);
}
JNIEXPORT void Java_app_organicmaps_sdk_cluster_ClusterMap_nativeSetPoiVisible(JNIEnv *, jclass, jlong handle,
                                                                               jboolean visible)
{
  g_framework->SetNavigationViewPoiVisible(handle, visible);
}
JNIEXPORT jdouble Java_app_organicmaps_sdk_cluster_ClusterMap_nativeGetCurrentZoomLevel(JNIEnv *, jclass, jlong handle)
{
  return g_framework->GetNavigationViewZoom(handle);
}
JNIEXPORT jdoubleArray Java_app_organicmaps_sdk_cluster_ClusterMap_nativeGetCameraAhead(JNIEnv * env, jclass)
{
  auto & routing = frm()->GetRoutingManager();
  routing::SpeedCameraOnRoute camera;
  double distance;
  if (!routing.IsRoutingFollowing() || !routing.GetSpeedCamManager().GetCameraAhead(camera, distance))
    return env->NewDoubleArray(0);
  auto const latLon = mercator::ToLatLon(camera.m_position);
  double const values[] = {distance, camera.NoSpeed() ? 0.0 : camera.m_maxSpeedKmH / 3.6, latLon.m_lat, latLon.m_lon,
                           routing.IsSpeedCamLimitExceeded() ? 1.0 : 0.0};
  auto result = env->NewDoubleArray(5);
  env->SetDoubleArrayRegion(result, 0, 5, values);
  return result;
}

JNIEXPORT jdoubleArray Java_app_organicmaps_sdk_cluster_ClusterMap_nativeGetRouteMetrics(JNIEnv * env, jclass)
{
  double values[3] = {};
  auto const * route = frm()->GetRoutingManager().RoutingSession().GetRoute();
  if (route && route->IsValid())
  {
    values[0] = route->GetCurrentDistanceToEndMeters();
    values[1] = route->GetTotalDistanceMeters();
    routing::turns::TurnItem turn;
    route->GetNearestTurn(values[2], turn);
  }
  auto result = env->NewDoubleArray(3);
  env->SetDoubleArrayRegion(result, 0, 3, values);
  return result;
}
}
