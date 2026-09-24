# Automotive renderer

The Android `auto` app selects the SDK `auto` variant. Other app flavors select
`standard`. CMake `OMIM_AUTO` is off by default. Substantial automotive behavior
is guarded by `#ifdef OMIM_AUTO`; building multiple app variants together does not
share their native library. The published SDK uses `standardRelease`.

```sh
cd android
./gradlew :app:assembleAutoRelease -Parm64
./gradlew :sdk:assembleStandardRelease -Parm64
```

## Per-device configuration

Edit `android/sdk/src/main/res/xml/vehicle_rendering.xml`. It uses the existing
`DeviceDetector` family/model identifiers from the sensor configuration. Profiles
are read once; defaults, a matching family profile and a matching model profile
are applied in increasing specificity, regardless of their order in the file.
Unspecified fields inherit. Each display is configured independently.

```xml
<render_config>
  <profile name="default">
    <main render_scale="1.0" max_fps="0" msaa_samples="0" />
    <cluster render_scale="1.0" max_fps="0" msaa_samples="0" />
  </profile>
  <profile name="rox" type="car" family="rox">
    <main render_scale="1.0" max_fps="30" msaa_samples="0" />
    <cluster render_scale="1.0" max_fps="20" msaa_samples="0" />
  </profile>
</render_config>
```

The default profile uses native resolution without a software FPS limit or MSAA.
ROX uses native resolution at 30 FPS on the main display and 20 FPS
on the cluster, both without MSAA. Default displays require an explicit `max_fps`;
use `0` to disable the limiter.
Device overrides can add `type="car"` and `family` using the detector's identifiers;
a model-specific profile also adds `car_type` with the value supplied by the detector.
The parser rejects missing defaults, duplicate selectors, unknown attributes and
out-of-range values, including profiles not selected on the current device.
Ordinary app flavors retain their existing rendering settings.

| Attribute | Values | Meaning |
|---|---|---|
| `render_scale` | 0.5–1.0 | Raster resolution of the ground and buildings |
| `max_fps` | 0–240 | Automotive FPS limit; 0 disables it, including during guidance |
| `msaa_samples` | 0, 2, 4 | Hardware MSAA for the ground/buildings; 0 disables it |

Scaling and MSAA currently target OpenGL. Geographic tile coverage, projection,
surface dimensions and touch coordinates stay native. Text, navigation route,
symbols, selection, position arrow and UI keep native resolution. This setting
is independent of the cluster's existing visual scale for fonts/symbols.
MSAA renders into multisampled storage and resolves before compositing; unsupported
sample counts fall back to a supported lower level. The actual allocated sample
count is checked, so driver rounding cannot silently increase the configured
budget. Unsupported framebuffers retain the normal rendering path. Software FPS
limits do not disable the display/compositor VSync.

On Android 10+ with OpenGL, capped rendering starts on Choreographer display ticks.
Each renderer keeps its own cadence, skips missed slots and does not accumulate
render time into the next deadline. Fractional rates can alternate display tick
intervals. Waiting happens before scene updates and sleeps in the render thread's
looper; it is interrupted for pause/destruction. No callbacks are reposted while
the renderer is idle. Older Android versions and other backends retain timed
waiting. This is a ceiling, not a guarantee that a heavy frame meets its deadline.
The native display clock follows the Android primary display; a virtual cluster's
final physical presentation still depends on its compositor/output pipeline.

## Rendering changes

- Reader threads send geometry/overlays as one completed tile batch. Cancelled
  reads release CPU data; obsolete generations are checked before GPU work.
  Coverage lookup remains independent of generation because viewport keys do not
  carry one. Only completed reads use the strict generation check.
- Camera sectors with the same local origin and minimum zoom share a batcher.
  The frontend still replaces the full area snapshot atomically.
- Each renderer owns a GL buffer cache capped at 16 MiB of unused storage. A frame's
  retired buffers share a fence; reuse polls without waiting. Logical vertex/index
  capacities remain separate from cached storage capacity. Context teardown clears
  the cache while a shared GL context is current. This reuses whole buffers; it is
  not a suballocator combining arbitrary base-map tiles into one VBO.
- Driving POI filtering starts at 10 km/h. The full set returns after a continuous
  minute at or below 3 km/h; unknown speed does not count as a stop. Fuel, charging,
  parking, road services/rest areas, motorway junctions, cameras and toll booths
  remain eligible. Building geometry, road labels, road events and separate
  search/selection marks retain normal rendering. The existing POI visibility
  switch takes precedence.
- Automotive position markers retain double-precision world coordinates until
  conversion to local rendering coordinates, avoiding pixel jumps at high zoom.
- Switching render targets restores viewport/scissor before clearing, so a smaller
  pass cannot leave stale native-resolution color/depth outside its old bounds.
- External event queries normalize wrapped world coordinates, including split
  viewports across the antimeridian. Their area fills follow the nearest world copy.
- The cluster hides dummy cameras and video surveillance. Speed, intersection and
  lane-control cameras and other road warnings retain the user's category settings.

Regression tests are in `drape_tests` (GLBufferPool/GPUBuffer, FrameCadence),
`drape_frontend_tests` (TileReadBatch, DrivingPoiPolicy, UserAreas, ScaledTarget,
MyPosition precision), and SDK `RenderConfigTest`. Configure C++ with
`-DOMIM_AUTO=ON` to exercise the guarded paths. Graphics tests require a real GL
context: a Qt plugin that skips context creation does not validate them merely
because the runner exits successfully. Device performance comparisons use both
actual screens and report presented frames, CPU, memory and queue recovery.

## Planned camera coverage filtering

During guidance, show coverage gradients only for cameras on the selected route.
During driving without guidance, limit gradients to cameras on the street/road
currently being travelled. Add a settings toggle to hide camera coverage gradients
without disabling camera icons or warnings. Implement these together in the next
gradient-filtering session; they are not implemented here.

Also planned for that session: a user setting for POI density (the number of
objects shown on the map), independent of camera coverage gradient visibility.

Investigate rear-facing cameras in that session too: match the camera to the
travelled carriageway and directed road graph, verify the provider's azimuth
semantics, and retain applicable coverage after passing the camera. Road direction
alone must not turn an opposing-flow camera into a rear-facing camera; individual
lane geometry is not always available.
