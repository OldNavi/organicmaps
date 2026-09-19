#include "Framework.hpp"
#include "geometry/mercator.hpp"
#include "routing/route.hpp"
#include "routing/speed_camera.hpp"
#include "routing/speed_camera_manager.hpp"

extern "C"
{
JNIEXPORT jlong Java_app_organicmaps_sdk_cluster_ClusterMap_nativeCreate(JNIEnv * env, jclass, jobject surface,
                                                                         jint dpi, jint zoom, jboolean showPoi,
                                                                         jboolean buildings3d, jdouble tilt,
                                                                         jdouble anchorX, jdouble anchorY)
{
  return g_framework->CreateNavigationView(env, surface, dpi, zoom, showPoi, buildings3d, tilt, anchorX, anchorY);
}
JNIEXPORT void Java_app_organicmaps_sdk_cluster_ClusterMap_nativeDestroy(JNIEnv *, jclass, jlong handle)
{
  g_framework->DestroyNavigationView(handle);
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
