# Android cluster integration

Organic Maps supplies navigation data and independent map presentations. A host such as RoxPremium
continues to own its QNX window, virtual displays and instrument-cluster overlays.

The `auto` flavor uses package `app.organicmaps.auto` (`app.organicmaps.auto.debug` in debug).
The application manifest advertises integer metadata `gwclub-version=1`.

## Provider

The fixed authority is `organicmaps.auto.navi` for every build flavor, including debug.
Only one Organic Maps variant declaring this provider can be installed at a time. Clients need
`android.permission.ACCESS_FINE_LOCATION`. Android package visibility may also require a provider
entry in the client's `<queries>` manifest element. Organic Maps itself needs location permission.

Read with `ContentResolver.query()` and observe each URI with `ContentObserver`. Values are published
from the navigation owner and location updates, at most four location-driven snapshots per second.
Stopping guidance clears navigation data immediately. These are snapshots, not a freshness guarantee
for GPS or speed-limit data.

Each URI is notified independently when its published data changes. Lane and direction-sign
comparisons include distance and display units, as in Yandex Auto; unchanged artwork alone does not
make the whole response equal. Clients can reuse lane drawables when only distance changes.
Fresh GNSS observations still notify `/guidance` and an active `/speed_camera` so ISA and camera
passage tracking retain measurement freshness. Display-speed updates continue to notify `/speed`
and `/guidance` independently. Expiry and route cancellation notify consumers to clear old data.

| Path | Contents |
| --- | --- |
| `/api_version` | Protocol version (`version=1`) |
| `/speed` | Display speed (MCU, kinematic sensor, then GNSS), validity, source and age. |
| `/guidance` | Guidance state, remaining/total distance, remaining time, speed limit, current road |
| `/maneuver` | Maneuver action, next road, distance and roundabout exit number |
| `/lanes` | Direction lists and the recommended direction for each lane |
| `/speed_camera` | Camera ahead, distance along the route, speed limit and overspeed indication |
| `/direction_signs` | Available next-road shields, with `kind=road` |
| `/routes` | The current guidance route (index 0, selected); not alternative-route planning |

Numeric distances are **metres**, times are **seconds**, and both `speed_limit` fields are
**metres per second**, matching the RoxPremium consumer. Display strings/units follow Organic Maps
measurement settings. Unknown speed is zero. An absent camera has an empty `camera_id`, zero speed
and `distance=Integer.MAX_VALUE`, so older Premium clients do not show a camera at zero metres.

Maneuver and lane names match the existing Premium contract. City/region, destination pictograms,
and unavailable destination-sign content are not invented. Road shields have a neutral background.
Without guidance, matched-road limits and cameras are provided by the road-info worker described below. There is no Yandex traffic
feed or Yandex POI/search API in this integration.

The exported `app.organicmaps.cluster.NavigationReadyService` initializes the native core
and owns a location session for a bound host without opening the main Activity. It requires the same location permission. A
successful binding does not mean map downloads or GPS acquisition have completed.

## Driving without a route

Real GPS fixes feed a separate native road-info reader on one worker thread. It matches
position and heading against car-accessible road geometry, requires consecutive matching
fixes, rejects ambiguous nearby roads, and reads numeric directional/conditional maxspeed
from the downloaded map. Routing cost speeds and unknown/unlimited values are not presented
as legal numeric limits. Fresh stationary fixes retain the approach direction.

The nearest camera is searched up to 2 km ahead, following road geometry and stopping at an
ambiguous junction. Distance follows the road instead of a straight-line radius. This uses
existing MWM camera data and country exclusions. The existing camera format reader ignores
camera enforcement direction because of data quality, so this is not a guarantee of which
lane/direction a camera enforces. No online camera feed is added.

Without a route, `/guidance` keeps `state=none` and empty route/ETA fields, but can report
`speed_limit` and `current_road`; `/speed_camera` can contain a camera ahead. Guidance adds
`speed_limit_valid`, `road_matched`, and `fix_age_ms`. Distances/speeds keep their existing
metre and m/s units. A snapshot expires after 5 seconds without a fresh GPS fix and observers
are notified of expiry. A provider query never refreshes the original measurement time.
During active guidance, route-based information takes precedence.

Binding `NavigationReadyService` now owns a foreground location session, allowing Premium
to consume road information with no map window and no route. Unbinding the last client
releases that session; other cluster/navigation/track-recording owners retain their GPS.
Read-only queries alone do not start a location session. Location permission is required.

Validation: four native ARM64 tests cover direction/ambiguity, distance along a bend,
branch ambiguity and a camera behind a reverse-moving vehicle. A device integration test
on the downloaded Moscow map reported 30 km/h on Bolshoy Gnezdnikovsky Lane with no route
and confirmed that the provider clears the limit after GPS expiry. No system mock provider
or vehicle-control commands are involved in that test.

## Automotive vehicle speed

Sensor sources are configured in `android/sdk/src/main/res/xml/vehicle_sensors.xml`.
See [vehicle sensor profiles](VEHICLE_SENSORS.md) for the hierarchy, car detection, source fields,
privileged signature requirements and motion-sensor processing. The old independent
`can_speed_*` bool/integer/dimen resources are no longer used.

The car/default speed source remains VHAL `PERF_VEHICLE_SPEED` (291504647), global area 0,
in meters/second. Phone/default disables the extra speed source and keeps GNSS speed.
Numeric scalars and arrays are supported; `value_index` selects an array element.
`multiplier` is a dimensionless XML number, independent of display density.

The reference Yandex Auto 2.3.3.5921.0 uses the former `can_speed_*` resource names. In the inspected APK,
`Sa/j` polls once per second, tries `Float[]` first and then a scalar Float, and multiplies the
result. Its bundled property is vendor ID 560010980 (`0x216116E4`), not the standard speed ID.
`B5/g` selects the CAN speed provider when available and the MapKit speed provider otherwise.
This is evidence of speed-source selection, not proof of Yandex's coordinate prediction algorithm.

OM makes an initial generic `getProperty(propertyId, 0)` read and registers a callback, requesting
10 Hz for continuous properties within their advertised sample-rate range. Car connection and
property calls run on a worker, with a nonblocking Car-service connection. Unavailable/error
status clears the sample; timestamps use elapsed-realtime nanoseconds. Out-of-order, future,
nonfinite, above 75 m/s in magnitude, or older-than-two-seconds samples cannot replace GNSS speed.
An AVAILABLE property returning only zeros is initially untrusted. The first valid nonzero
measurement arms it, after which zero is a valid stop; temporary invalid data does not erase
that trust, but reconnecting Car Service resets it. Disconnect clears cached
data and unregisters the callback. Signed speed is available from `VehicleSpeedSource`; Location
receives its magnitude. Mock locations and route simulation retain their own speed.

`CAR_SPEED` and `CAR_VENDOR_EXTENSION` are declared. Standard speed requests `CAR_SPEED` once
when the UI is opened, if necessary. A vendor property checks `CAR_VENDOR_EXTENSION`, which
must be granted by the platform; it is not a runtime-dialog permission. Without permission or
an available property, GNSS speed remains in use, including service-only starts.

Vehicle speed is applied to each newly accepted GNSS fix for the existing location consumers.
It also reaches the native extrapolator as an independent stream, retaining each measurement's
monotonic timestamp. Position updates are predicted every 200 ms, integrating speed over time
along the latest GNSS course, with or without an active route. Neither this stream nor its
predictions are injected into Android's location provider or the raw GNSS track recorder.

The horizon is two seconds from the GNSS measurement (including delivery delay and Android
suspend time), and speed freshness is independently limited to two seconds. Unknown intervals
are not filled retroactively. An invalidated speed freezes the last prediction until a fresh
GNSS anchor; subsequent fixes can use ordinary GNSS extrapolation if vehicle speed is unavailable.
A confirmed zero stops advancement. A change between forward and reverse waits for a new GNSS
course, because GNSS bearing describes movement rather than the body's orientation. New GNSS
fixes reset accumulated displacement and correct the rendered pose through the existing animation.
Without a trusted VHAL sample, the existing route-based GNSS extrapolation remains in effect.

`VehicleSpeedCacheTest` covers scalar/vector conversion, reverse/zero speed, freshness and
property-group permissions and the zero-value trust trigger. `vehicle_motion_tests` covers
integration, acceleration/stopping, stale/invalid data, delayed samples, reverse and GNSS resets.
`VehicleSpeedIntegrationTest` checks the packaged XML profile and can read the configured property on a head unit
with `-e carSensors true`; a trusted nonzero value must be observed during this opt-in test.
It never writes VHAL properties.

## Map connections

Create an Android display accessible to Organic Maps (for a different UID, normally a public
VirtualDisplay). Its output Surface can be the SurfaceView in the host's existing cluster window.
The default Android display cannot be used as a cluster target.

```java
Uri provider = Uri.parse("content://organicmaps.auto.navi");
Bundle request = new Bundle();
request.putInt("displayId", virtualDisplay.getDisplay().getDisplayId());
request.putDouble("tilt", 45); // Optional: omit or use "auto" for automatic perspective.
request.putString("anchor", "0.5,0.85"); // Optional: default is 0.5,0.75.
request.putInt("3d", 1); // Optional: enable 3D buildings; omitted/0/false means flat buildings.
request.putInt("poi", 1); // Optional: show POI. Omission, 0, or false hides POI.
request.putInt("zoom", 16); // Fixed integer 1..20. Omit zoom, use 0, or putString("zoom", "auto") for autozoom.
request.putBinder("token", clientLifetimeBinder); // Optional, retained by the host.
resolver.call(provider, "show_cluster", null, request);
// Repeating show_cluster for the same client/display updates zoom without reopening it.
resolver.call(provider, "hide_cluster", null, request);
```

The legacy query commands also work:

```
/show_cluster?displayId=5&zoom=16&poi=1&package=com.gw.club.roxpremium
/show_cluster?displayId=5&zoom=auto&package=com.gw.club.roxpremium
/show_cluster?displayId=5&package=com.gw.club.roxpremium
/hide_cluster?displayId=5&package=com.gw.club.roxpremium
```

`package` is accepted for compatibility; ownership comes from the actual calling UID, never from
that argument. `accepted` means the asynchronous service request was dispatched, not that the GPU
has already produced a frame. Display disappearance and an optional Binder token's death release
only the affected connection. Repeated hide is harmless. A different UID cannot replace or hide
another client's presentation. Closing the foreground notification closes all cluster connections.

Each display gets its own GL context pair, drawing engine, tile-reading workers, DPI, viewport,
animation state and zoom mode. Autozoom is the default and uses Organic Maps speed-based scaling;
a supplied integer selects a fixed zoom. Repeating show_cluster without zoom returns that display
to autozoom. Fixed zoom updates apply immediately even without a calculated route; the first GPS fix snaps
the cluster to its initial position instead of animating from (0,0), preserving initial camera options;
cluster autozoom is not delayed by the interactive-map follow timer; rapid updates
apply the latest request. There is no separate zoom command. The primary map may use Vulkan or GL. Connections are not limited
to one or two; practical limits are the vehicle's memory/GPU and Android display resources.

Camera options accepted by both query parameters and `ContentResolver.call()` extras:

| Parameter | Default | Values |
| --- | --- | --- |
| `tilt` | `auto` | With fixed zoom: `auto` or an angle from 0 to 55 degrees; 0 is a top-down view. Ignored with auto zoom. |
| `scale` | `1.0` | DPI multiplier from 0.5 to 3.0; fractional values are accepted |
| `anchor` | `0.5,0.75` | `x,y` fractions of the visible map, each from 0 to 1 |
| `anchor_x`, `anchor_y` | `0.5`, `0.75` | Alternative to the compact `anchor` parameter |

Do not combine `anchor` with `anchor_x`/`anchor_y`. Coordinates start at the top-left corner:
`anchor_y=0.85` puts the marker lower on the screen. The arrow remains fixed at the anchor and
points upwards while the map follows position and bearing. With `zoom=auto`, `zoom=0`, or omitted zoom,
perspective is always scale-dependent, using the same automatic camera projection as the primary map.
A numeric `tilt` only applies with fixed zoom; `tilt=auto` also enables automatic perspective with fixed zoom.
Omitting options on a
repeated request restores their defaults, so include every non-default option on each update.

`scale` multiplies the renderer's DPI-derived visual scale once, scaling labels, icons, roads and
the position marker together. It is applied after OM's minimum-DPI normalization, so 1.5 really
means 1.5 times the default size, within the renderer's supported visual-scale range (1–4).
The primary map's large-font preference does not affect cluster labels. The Framework's base
font-scale factor is retained separately from the per-display scale. A changed
scale refreshes that display's graphics resources; unchanged values do not restart rendering.
Organic Maps defaults to `1.0`; RoxPremium explicitly sends `1.5` for its 160-DPI virtual display.

```
/show_cluster?displayId=2&zoom=15&tilt=45&anchor=0.5,0.85
/show_cluster?displayId=2&zoom=18&tilt=35&anchor=0.5,0.85&scale=1.5
/show_cluster?displayId=2&zoom=auto&anchor_x=0.5&anchor_y=0.75&poi=1
```

POI icons and POI names are hidden by default on each cluster display. Add `poi=1` to
`show_cluster` to show them; `poi=0` or omitting the parameter hides them, including on an
already open presentation. `ContentResolver.call()` also accepts a boolean `poi`. Repeated
commands update the existing display and re-read its tiles without requiring a zoom change.
POI visibility is independent for each display and does not change the primary map. Street and
settlement names, house numbers, building/area geometry, the route and position marker remain.
The POI category follows Organic Maps' feature classifier (shops, amenities, attractions, etc.).

Cluster maps render buildings as flat footprints by default. Add `3d=1` to `show_cluster`
to enable 3D buildings, or use `3d=0`/omit the parameter to return to flat buildings. The call
variant also accepts a boolean `3d`. Repeated commands update only that display without
recreating it. Camera tilt, the position arrow and the primary map are independent of this flag.

Presentations contain only the map, route and position marker. They have no touch controls,
place pages, navigation panels or debug widgets. By default the marker follows the car at horizontal centre
and 75% of the visible height; map bearing follows the car. The route/GPS source is shared, while
camera manipulation on the primary map does not control cluster cameras. Map style/day-night
currently follows the application globally; independently selected per-display style families
are not implemented. The automotive color adaptation is described in
[data/styles/auto/README.md](../data/styles/auto/README.md).

Android theme changes refresh retained geometry and textures for both OpenGL and Vulkan,
including service-only operation: `MwmApplication.onConfigurationChanged()` updates the effective
theme, and a cluster synchronizes it before creating its renderer. System mode uses the latest
Application `uiMode`, not a possibly stale Presentation context. Explicit light/dark preferences
and scheduled-theme callbacks use the same path and do not require the main Activity.
This also covers activity surface detachment during a configuration change. Marking a style
while the primary renderer is suspended queues a refresh for resume; it cannot merely change
the global palette because existing tiles may survive. This prevents old-theme roads/areas
from remaining until a pan or zoom loads replacement tiles.
`themeMarkedWhilePrimaryPausedRecolorsRetainedTiles` compares both day/night transitions with
fully refreshed reference images, keeping the camera fixed and a second map connected.
On CB_IHU20 the old path failed with 27,525/82,944 pixels retaining the wrong theme. The fixed
path passed both transitions with presentation paused and with the surface detached while
retaining its graphics context; the three-map theme/reconnect regression also passed.
Instrumentation used release native code with Java shrinking disabled for test-runner
compatibility. The native library was byte-identical to the final optimized release APK.

The foreground location service keeps updates active while a cluster display is connected.
The `auto` flavor also declares `ACCESS_BACKGROUND_LOCATION`; it must be granted separately
from declaring it in the manifest (for a provisioned head unit, `adb shell pm grant
app.organicmaps.auto android.permission.ACCESS_BACKGROUND_LOCATION` after foreground location is granted).
Modern Android background-start and while-in-use permission rules still apply; a host must start
connections from a context allowed by its OS. Map files must be downloaded in Organic Maps.

## RoxPremium client changes

Existing Premium is hard-coded to `yandex.auto.navi`, the Yandex package and its ready service.
To select Organic Maps as a source, use the authority/package above and bind to
`<applicationId>/app.organicmaps.cluster.NavigationReadyService`; use the same selected authority
for both navigation observers and show/hide commands. Keep Premium's existing VirtualDisplay,
QNX window, masks and information widgets. Do not install a second provider with Yandex's authority.

This branch changes Organic Maps, not the separate RoxPremium checkout. Yandex resource-overlay
zoom settings do not configure Organic Maps: send `zoom` with each show/update command instead.

## Voice search and route commands

Organic Maps accepts these `ACTION_VIEW` links through its existing entry Activity:

```
om://map_search?text=АЗС
om://map_search?text=кафе&lat=55.7522&lon=37.6156&locale=ru
om://build_route_on_map?lat_to=55.7522&lon_to=37.6156&name_to=Destination&start_guidance=1
om://build_route_on_map?place_to=12345&start_guidance=1
om://build_route_on_map?place_to=home&start_guidance=1
om://build_route_on_map?place_to=work&start_guidance=1
```

Use `Uri.Builder.appendQueryParameter()` to encode names/queries. Search runs in Organic Maps'
offline search UI, with the optional coordinates as a search centre. Map data must cover the area.
Routes use the vehicle router. Omitting `start_guidance` opens planning; `start_guidance=1` starts
only after a successful build from the current position, selecting the alternative with the smallest
estimated travel time before following it. Manual route selection is unchanged. Missing maps, warnings or other build
failures do not start guidance. Explicit `lat_from`/`lon_from` are supported for planning; an
arbitrary origin does not trigger automatic guidance. Cancellation/new endpoint selection clears
a pending automatic start.

`/bookmarks` publishes `id,title,lat,lon` (ID and title are the first two columns used by
RoxAssistant). It is restricted to this app, system UID and local adb shell/root, in addition to
the provider's location permission. Initialize the core through the ready service or the main
Activity before reading saved places; observe `/bookmarks` for completion of asynchronous loading.
IDs refer to the currently loaded bookmark generation: reread after restart/loading notifications. `home`/`work` require exactly one bookmark named Home/Work
or the corresponding localized name (Дом/Работа in Russian). Missing or ambiguous names produce
a message instead of choosing an arbitrary destination.

The existing `om://search` and `om://route` APIs are unchanged. Yandex URI schemes are not registered
by Organic Maps. RoxVoiceManager needs Organic Maps in its supported-package selection, the `om`
scheme for these operations and `organicmaps.auto.navi` for bookmarks. Its existing
Premium relaunch/display selection can still be used. The separate RoxVoiceManager checkout is
not modified by this branch.

`along_route=1` is recognized but explicitly reported as unsupported: an unrestricted search is
not presented as a search along the route. This requires a separate route-corridor search feature.

## Stock ROX voice

For flavor `auto`, the first initialization without a saved voice-backend choice detects ROX
using Weather's rules: `persist.car.sn` must be nonblank and
`persist.sys.aptiv.serial.number` must be blank (Aptiv takes precedence). Detection reads properties
without logging serial values. On a detected ROX, the app selects the stock voice backend and
enables voice instructions. A saved manual backend choice is preserved on later starts, and
other flavors do not perform this automatic selection.

Voice instructions settings have an optional **Built-in ROX voice** switch on devices with the
stock launcher and TTS service packages. It loads `com.roxmotor.ttsmanager.TtsManager` from
the installed `com.roxmotor.launcherapp` SDK and binds to `com.roxmotor.ttsservice`.
`speak(text, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)` selects navigation usage 12
for both audio focus and playback. The old `VoiceManager.speak(text)` path selected assistant
usage 16 in the stock service.
No car SDK binaries or system UID are added to Organic Maps. The app's choice does not change
Android's default TTS engine or the vehicle's voice/volume settings. Route instructions use the
system locale supported by Organic Maps; the existing Android TTS language is retained when
switching back. The existing voice-test button exercises speech on demand. `stop(12)` is scoped
by the SDK to the Organic Maps package. The service manages audio focus itself.

The connection listener is registered before `init`; after each `READY` callback the adapter
registers the TTS status listener again. `speak` returns null even on success in the asynchronous
2.0.0 SDK, so its return value is not used as an utterance ID. Playback errors are reported via
TTS callbacks without marking the service disconnected. Closing the adapter stops its speech,
removes both listeners and calls `unInit`.

Availability depends on the installed ROX firmware/SDK. Readiness failures remain visible in the
voice settings and users can switch back to Android TTS. A connection/readiness test alone does
not prove audible speech quality.

## Frame rate limits

The `auto` flavor limits the primary map to 30 FPS, including manual panning and zooming.
Each independent cluster renderer is limited to 20 FPS, with or without 3D buildings.
The limits apply in both debug and release builds. Other flavors retain the primary map's
existing pacing (including its 30 FPS route-following limit).

Each renderer waits only for the unused part of its own frame interval, including CPU rendering
and presentation time. A slow frame receives no additional delay, and an unchanged map retains
the existing idle wait. The limits are upper bounds, not guaranteed frame rates, and do not
change the frequency of GPS updates or navigation calculations.
`mainAndClusterFrameRatesAreLimitedIndependently` exercises a primary map and two clusters
under continuous updates and checks their separate 30/20/20 FPS bounds.
On CB_IHU20, the six-second autoDebug test captured 175 primary frames and 111/109 cluster
frames (29.2/18.5/18.2 FPS); the second cluster had 3D buildings enabled. Theme changes and
disconnect/reconnect also passed with the limits enabled. The high-tilt driving benchmark
with the cap captured 111 frames in 6,023 ms and used 5,707 ms of summed process CPU time.

While guidance is active, hiding the main map and closing all clusters does not stop
`NavigationService` or location updates. `NavigationProvider` listens at application scope
and publishes GPS-triggered snapshots at most once per 250 ms, independently of rendering.
Forced process termination is different: the current service does not automatically restore
the route after a process restart.

## High-tilt rendering

Flat vector cluster maps now select tiles against the visible ground polygon, with a preload
margin, instead of reading the entire axis-aligned bounding rectangle. From 35 degrees upwards,
the distant part uses up to three coarser data-grid levels. The band around the car keeps full
detail and follows a custom anchor. Hysteresis prevents small GPS changes from repeatedly
switching a cell's detail level.

Road/area boundaries retain the original geometry resolution, and road widths use the camera's
zoom, so adjoining detail levels do not introduce mismatched road centrelines or widths. The
coarser distant styles/index levels omit small features and labels. Retired parent/child cells
remain visible until replacements are ready. Coverage snapshots are atomic; re-entered cells
receive fresh generations so cancelled reads and coalesced requests cannot leave stale tiles.
The revisit history is bounded.

This optimization applies to cluster vector maps with `3d=0` (the default). The primary map,
3D-building mode and raster backgrounds retain their existing tile-selection paths.

A controlled ARM64 **autoDebug** benchmark before adding the 20 FPS cap on CB_IHU20 used the downloaded Moscow map
(version 260714), a 1280x480 offscreen display at 240 DPI, zoom 18, tilt 55, anchor 0.5,0.85,
flat buildings and hidden POI. After loading the initial view, the same six-second sequence
moved the camera and rotated its bearing from 0 to 90 degrees:

| Measurement | Before | Optimized |
| --- | ---: | ---: |
| Process CPU time, summed over threads | 14,728 ms | 7,210–7,505 ms |
| Captured output frames | 104 | 159–161 |
| Output frames with changed map pixels | 81 | 139–142 |
| Requested tiles at the start | 45 | 15 |
| Requested tiles at the end | 40 | 9 |

The optimized column includes two runs of the same scenario.
The bottom 40% of the compared frames retained the near-car image (no channel differences
greater than 8/255; the final frame's near region matched exactly). This is one controlled
benchmark, not a guarantee of a particular frame rate on every device or route.
`ClusterMap.getTileStats()` reports requested, bounding-rectangle, coarse and pending tile
counts for diagnostics. `highTiltDrivingPerformance` records CPU time and captured frames;
`adaptiveTilesSurviveTurnsZoomAndBuildingModeChanges` checks reverse turns, zoom changes,
3D-mode switching and return to the original near-car image.

## Verification

Validated on 2026-09-19 with the ARM64 Google debug build and a CB_IHU20 head unit:
84 app/SDK unit tests passed; app/SDK debug lint completed without errors. Native/APK assembly
was run separately from lint because this project's lint invocation disables CMake build tasks.
All six device feature tests passed (three display tests, one GPS/marker test and two stock-voice
checks). Direct `set_mock_location`/`clear_mock_location` calls also passed without launching an
Activity or instrumentation; GPS was restored and MOCK_LOCATION returned to its default mode.
The rendering checks use offscreen displays; stock voice checks do not speak. The additional
POI image-comparison test passed on `autoDebug` with package `app.organicmaps.auto.debug`.

- Android ARM64 native library and Google debug/test APK builds.
- Unit tests for connection ownership, multiple displays, reconnect/cancel ordering, maneuver and
  lane mapping, metric/imperial display distances, speed units, immutable snapshots, zoom parsing
  and voice search/route command parsing.
- `ClusterRenderingTest`: real offscreen VirtualDisplays, different dimensions/DPI, primary plus
  two independent cluster renderers, light/dark changes without zoom, disconnect/reconnect,
  default/explicit autozoom, actual native camera zoom (including rapid updates without a route),
  independent zoom on the second display and `gwclub-version` metadata.
- Perspective coverage geometry was exercised on ARM64 across 1800 zoom/tilt/bearing/anchor and
  world-wrap combinations: complete visible-ground coverage, full detail at the car, non-overlapping
  cells, stable LOD, preserved key identity and invalidation of coalesced/re-entered cells.
- `firstRequestAppliesCameraWhenGpsAlreadyExists`: the first request at zoom 18, tilt 5 and
  anchor 0.5,0.85 with a cached GPS fix, plus immediate speed-based autozoom on a new display.
- `autoZoomUsesAutomaticPerspectiveDespiteManualTilt`: scale-dependent angles at different speeds,
  ignoring a supplied manual tilt for auto/zero/omitted zoom, restoring fixed tilt and preserving anchor.
- `buildingsAreFlatByDefaultAndCanBeEnabledIndependently`: image comparison of flat/3D buildings,
  live `3d=1` updates, omission/zero resetting the flag and an unchanged second display/tilt.
- `cameraTiltAndAnchorAreAppliedIndependentlyAndResetToDefaults`: actual native angles 0/30/45/55,
  marker positions on displays with different DPI, fixed tilt with fixed zoom/GPS bearing changes,
  screen-up arrow orientation away from the centre, and return to automatic tilt/default anchor.
- `RoxVoiceDefaultsTest` and the ROX device check cover persist-property detection, Aptiv
  precedence, auto-only selection and preservation of a saved voice-backend choice.
- `poiAreHiddenByDefaultAndCanChangeOnOneDisplayWithoutZoom`: image comparison with a map containing POI
  (Moscow by default, or `latitude`/`longitude` runner arguments for a fixture such as
  `data/minsk-pass.mwm`), default-hidden POI, live toggle, and an unchanged second display.
  The Minsk fixture is temporarily registered as `000001/Belarus_Minsk Region.mwm` because
  the app requires a catalog region name. The test injects coordinates into its own native core,
  leaves Android GPS untouched, and the fixture is removed after the run.
- `RoxVoiceIntegrationTest`: stock SDK connection/readiness and app backend selection/restoration.
  The opt-in `speaksOnNavigationChannel` case (`-e roxSpeak true`) speaks a test phrase and checks
  actual AudioTrack playback attributes for navigation usage 12; other cases do not speak.
- The opt-in `LocationManager` test validates real incoming fixes and the fixed marker on rendered frames; see [manual commands](MOCK_LOCATION.md).

Run device tests only on a test vehicle/head unit with location permission granted to the debug app.
The rendering test creates its own displays; it does not take over Premium's QNX cluster window.
A road test, live route recalculation and final visible QNX composition remain separate validation.
