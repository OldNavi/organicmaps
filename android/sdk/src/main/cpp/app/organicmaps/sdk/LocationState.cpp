#include "Framework.hpp"
#include "map/gps_tracker.hpp"

#include "app/organicmaps/sdk/core/jni_helper.hpp"

#include "app/organicmaps/sdk/platform/AndroidPlatform.hpp"

extern "C"
{
static void LocationStateModeChanged(location::EMyPositionMode mode, std::shared_ptr<jobject> const & listener)
{
  JNIEnv * env = jni::GetEnv();
  env->CallVoidMethod(*listener, jni::GetMethodID(env, *listener.get(), "onMyPositionModeChanged", "(I)V"),
                      static_cast<jint>(mode));
}

//  public static void nativeSwitchToNextMode();
JNIEXPORT void Java_app_organicmaps_sdk_location_LocationState_nativeSwitchToNextMode(JNIEnv * env, jclass clazz)
{
  g_framework->SwitchMyPositionNextMode();
}

// private static int nativeGetMode();
JNIEXPORT jint Java_app_organicmaps_sdk_location_LocationState_nativeGetMode(JNIEnv * env, jclass clazz)
{
  // GetMyPositionMode() is initialized only after drape creation.
  // https://github.com/organicmaps/organicmaps/issues/1128#issuecomment-1784435190
  ASSERT(g_framework && g_framework->IsDrapeEngineCreated(), ());
  return g_framework->GetMyPositionMode();
}

//  public static void nativeSetListener(ModeChangeListener listener);
JNIEXPORT void Java_app_organicmaps_sdk_location_LocationState_nativeSetListener(JNIEnv * env, jclass clazz,
                                                                                 jobject listener)
{
  g_framework->SetMyPositionModeListener(
      std::bind(&LocationStateModeChanged, std::placeholders::_1, jni::make_global_ref(listener)));
}

//  public static void nativeRemoveListener();
JNIEXPORT void Java_app_organicmaps_sdk_location_LocationState_nativeRemoveListener(JNIEnv * env, jclass clazz)
{
  g_framework->SetMyPositionModeListener(location::TMyPositionModeChanged());
}

JNIEXPORT void Java_app_organicmaps_sdk_location_LocationState_nativeOnLocationError(JNIEnv * env, jclass clazz,
                                                                                     int errorCode)
{
  g_framework->OnLocationError(errorCode);
}

static void UpdateLocation(jlong time, jdouble lat, jdouble lon, jfloat accuracyH, jdouble altitude, jfloat accuracyV,
                           jfloat speed, jfloat bearing, double ageSeconds)
{
  location::GpsInfo info;
  info.m_source = location::EAndroidNative;

  info.m_timestamp = static_cast<double>(time) / 1000.0;
  info.m_latitude = lat;
  info.m_longitude = lon;

  if (accuracyH > 0)
    info.m_horizontalAccuracy = accuracyH;

  if (accuracyV > 0)
  {
    info.m_altitude = altitude;
    info.m_verticalAccuracy = accuracyV;
  }

  if (bearing >= 0)
    info.m_bearing = bearing;

  if (speed >= 0)
    info.m_speed = speed;

  g_framework->OnLocationUpdated(info, ageSeconds);
  GpsTracker::Instance().OnLocationUpdated(info);
}

JNIEXPORT void Java_app_organicmaps_sdk_location_LocationState_nativeLocationUpdated(JNIEnv *, jclass, jlong time,
                                                                                     jdouble lat, jdouble lon,
                                                                                     jfloat accuracyH, jdouble altitude,
                                                                                     jfloat accuracyV, jfloat speed,
                                                                                     jfloat bearing)
{
  UpdateLocation(time, lat, lon, accuracyH, altitude, accuracyV, speed, bearing, 0.0);
}

JNIEXPORT void Java_app_organicmaps_sdk_location_LocationState_nativeLocationUpdatedWithAge(
    JNIEnv *, jclass, jlong time, jdouble lat, jdouble lon, jfloat accuracyH, jdouble altitude, jfloat accuracyV,
    jfloat speed, jfloat bearing, jdouble ageSeconds)
{
  UpdateLocation(time, lat, lon, accuracyH, altitude, accuracyV, speed, bearing, ageSeconds);
}

JNIEXPORT void Java_app_organicmaps_sdk_location_LocationState_nativeVehicleSpeedUpdated(JNIEnv *, jclass,
                                                                                         jdouble speedMps,
                                                                                         jdouble ageSeconds,
                                                                                         jboolean valid)
{
  // Sensor disconnects can race application/native initialization or teardown.
  if (g_framework)
    frm()->GetRoutingManager().OnVehicleSpeed(speedMps, ageSeconds, valid);
}
}  // extern "C"
