# Detailed road rendering

## Implemented prototype (2026-09-28)

The `routing-experiments` branch imports motor-road lane counts, directional lane
counts, `width:lanes`, directional widths and `turn:lanes`. Empty width slots retain
their position. Explicit widths win; a usable total `width` fills unknown slots;
remaining slots use 3.5 m. Widths are stored in centimetres with their source.
Turns use numeric masks. One-way `placement` positions (`left_of`, `middle_of`,
`right_of`) and `placement:start`/`placement:end` shift the physical road relative
to its source line. An implicit `placement=transition` derives endpoint offsets
from unambiguous neighboring road frames, without inventing paint. Directional
placement on two-way roads and malformed explicit counts remain excluded.
Roads without lane counts get a physical width
estimate by road class, without invented lane divisions. `lane_markings=no`
disables divisions.

Two optional MWM sections carry the data:

- `road_details`: profiles indexed by final feature ID. Version 2 adds start/end
  reference offsets; the reader also supports versions 0 and 1.
- `road_junctions`: shared OSM-node connectivity, arms, cut distances and local
  frames. Version 1 records continuations and endpoint tangents. Interior nodes
  are included, so a closed roundabout connects to its approaches without
  splitting the routing feature. Junctions are not inferred from coincident
  coordinates on unrelated OSM nodes.

Intermediate feature serialization carries lane profiles and the original road
node sequence as an optional trailing extension. Older records are still read.
The spatial index includes road footprints and junction patches. Immutable
section data is shared across active MwmValues through the existing MwmInfo
weak-cache lifetime; tile readers do not each expand the complete junction table.
They do not share FileReader's mutable cache. Junction data is read sequentially
into a temporary memory region and uses a sorted, flat feature-to-arm lookup,
avoiding per-field file-cache lookups and per-road hash/vector allocations.
The automotive renderer retains GPU tiles and contexts when its window surface
is hidden, so reopening the existing Activity does not rebuild the map scene. Existing maps without these sections keep the original renderer.
Existing routing lane guidance remains unchanged.

Under `OMIM_AUTO`, roads use metre-width ground ribbons from zoom 17, lane
divisions from zoom 18, and lane-aligned direction/turn arrows from zoom 19.
Generic frequent arrows along the road centre are suppressed for detailed roads.
Glyph size is bounded by lane width and spacing is measured in metres. Explicit
`through|through|right` is represented by two through arrows and a right arrow.
Paint colors come from the current style's named palette: `RoadLaneMarking`
controls dividers, while `RoadLaneSymbol` controls arrows and bus pictograms.
All six light/dark style variants define these entries in `style.mapcss`; light
styles use white and dark styles use dimmed light paint. The renderer does not
infer paint colors from the brightness of the road surface.

The junction model handles balanced one-way merges and splits, including
`1 + 1 -> 2` and `2 -> 1 + 1`, and width transitions. Turn masks constrain the
connections. A continuing carriageway keeps its original surface and markings;
matching endpoint frames close the seam between separate ways. A side road does
not erase the through lanes. Roundabouts are smoothed using their source geometry
and keep their contour when approaches attach. General junction patches are
reserved for transitions that require a common surface. Transition markings are
clipped to that surface. Roads have no artificial rounded end caps.

Side-road corner patches now use intersections of the actual offset contours from
neighboring MWM features, cached per tile. Short connector ribbons at elevated
road endpoints prevent a bridge-deck polygon from erasing the lower-layer
approach. Where deck geometry is available, these ribbons are clipped to that
deck and its layer; otherwise a short connector is used. They do not raise the
whole approach.
Road-name and shield layout excludes portions covered by a higher road layer at
detail zooms, including a glyph-height margin. Pedestrian bridges and bridges in
older maps use their style widths for this mask. Whole labels are laid out on the
remaining path segments rather than clipped through their letters.
The occlusion mask includes the uncut road ribbon: intervals reserved for
separate junction surfaces must not become holes in a bridge's label mask.

Missing lane counts are recorded separately from explicit `lane_markings=no`.
The generator can estimate width between mapped continuations of the same road
class and direction, following at most 1 km of connected geometry. Both ends
must reach a width anchor; differing anchors use the narrower width. Estimated
widths do not add lane paint or turn arrows. Explicit lane counts are retained.
This fixes short untagged bridge approaches without treating a side ramp as a
continuation of the main carriageway.

Width transitions take precedence over an equal-width side road. A wider branch
joining a narrow through road needs a common transition surface, including when
the join is an internal vertex of one feature. Roundabouts retain their original
contour. Junction surfaces are tessellated from their boundary instead of widened
to a convex hull. Branch surfaces and paint are subtracted from the continuing
carriageway footprint near a junction, including the overlap before an acute
merge reaches its centerline cut distance.
Two-arm width transitions inherit this boundary from a connected short link's
adjacent junction. Their fill and markings cannot protrude onto the through road
just because the width change is stored as a separate OSM node. Raised bridge
casings are clipped against the combined pavement of the bridge, junction and
connected approaches, retaining the outside border without dark blocks inside
the approaching carriageway.
Closely spaced general junctions connected by short roads can enclose narrow
unpainted pockets between their separate meshes. Their neighboring pavement is
combined before tile clipping; only narrow interior contours are filled by one
owner. This preserves the outside boundary and larger islands. Roundabout
geometry is excluded. This is a cartographic inference from lane widths, not
surveyed island geometry or an inference of hatched road markings.

Balanced merges and splits retain individual lane ribbons through the common
surface, including `1 + 1 <-> 2` and `3 <-> 2 + 1`. Their control points keep the lanes apart
instead of pulling both toward the OSM junction node. This also applies to a
two-way road separating into an incoming and an outgoing one-way arm.
Through-road ribbons also remain covered inside general junction surfaces,
preventing concave corner curves from cutting holes into the carriageway.

Lane connections order complete incoming/outgoing road bundles before the lanes
inside each bundle. A nearby ramp must not interleave with the main road's lanes
when their cut planes overlap in lateral projection. Matched connections carry
shared left/right boundaries. The pavement and its dividers use these same
vertices, and the divider mesh is clipped against the resulting junction surface.
Width transitions interpolate their boundaries along the same metric centre curve
as the pavement. Label masks also include the junction surface at its owner layer.
These algorithms were implemented locally; the survey in
[OSM_LANE_RENDERING_REFERENCES.md](OSM_LANE_RENDERING_REFERENCES.md) describes the
external design references without copying their code.

Automotive road-shield backgrounds require their text overlay to participate in
placement. A missing layout or unavailable glyphs hide the group before it can
displace other labels; ordinary collision handling still hides bound members
together.
The invalid and debug mark-ID sentinels are excluded from the external-mark
namespace. Otherwise a road-shield background receives a selection-only handle
and bypasses the GPU index mutations that hide a rejected overlay.

The generator imports per-lane `psv:lanes` and `bus:lanes` designations, including
forward/backward arrays. `designated` produces a bus pictogram in that lane;
`yes` alone does not. The flag uses a reserved bit in the versioned lane mask,
while turn flags remain separate in the decoded model. Explicit turn arrows
are retained beside the pictogram. A total `lanes:psv` count alone does not locate
a lane. This visual designation does not change routing access restrictions.

Geometry is prepared on tile workers and cached in the normal tile/GPU pipeline.
Dash phase is assigned before tile clipping. Shapes batch all surface/marking
triangles rather than allocating a render object for every dash. Sharp,
degenerate geometry retains the ordinary line fallback. This does not change
vehicle map matching or provide lane-level localization.

The sample is centred on the Besedinsky bridge (55.62333, 37.79305), with source
bounds `37.782,55.614,37.804,55.633`. It contains 194 road profiles, including
16 five-lane sections with explicit widths `350|350|375|375|375` cm (18.25 m).
The MWM contains geometry, spatial/search indexes, routing, access restrictions
and maxspeeds. It is a small extract: road continuations outside it are absent.
It must not replace a complete regional map in the working app.

The roundabout at the market entrance (OSM way 720574621) has neither `lanes`
nor `width` in this source. Its inferred width is not evidence of an actual
lane count. Exact paint, hatched islands, crossings and shoulder geometry need
additional explicit data; the schematic model does not reproduce a surveyed
road surface or establish legal lane connectivity at arbitrary intersections.

Artifacts are in `build-root-auto/road-detail-test/`. The demo APK uses the
separate `app.organicmaps.auto.profileable` package and a small test catalogue
containing `RoadDetailTest`; map data stays separate from the working app.
The data source is OpenStreetMap contributors, ODbL.

The profile dump can be regenerated with:

```sh
build-root-auto/generator_tool --dump_road_details --output=RoadDetailTest \
  --data_path=build-root-auto/road-detail-test --user_resource_path=data
```

Tests cover explicit/partial/invalid widths, driving direction and side, turn
masks, old intermediate/section records, concurrent reads, generated MWM
round-trip, physical edge selection, shared-node identity, roundabout internal
nodes, merge/split lane ordering, width transitions, marking clipping, lane
arrows, metric width, tile seams and the recorded roundabout geometry.

## Full regional test data

The regional build uses the user-provided `central-fed-district-260927.osm.pbf`.
Its replication timestamp is `2026-09-27T20:23:36Z`. The input is clipped to the
union of the stock Moscow, Moscow Oblast East and Moscow Oblast West borders,
with complete ways and multipolygon/boundary members retained where available.
Build commands and artifacts live in `build-root-auto/maps-260927/`.

An offline geometry audit of the generated regions exercised 619,963 road
profiles and 725,571 junctions without an assertion or crash. This checks geometry
construction, not visual fidelity at every junction or frame-time performance.
The small fixture remains useful for the recorded bridge and roundabout cases.

The automotive Activity refreshes themed overlay views on night-mode changes
while keeping the MapView attached. The head-unit search/theme test verifies
Activity identity, surface lifetime, preserved results, updated colors and active
route guidance on the full Moscow map.

## Design notes and subsequent stages

These notes describe the broader design; the implemented subset is listed above.
The first consumer is the auto flavor, on both the main map and instrument cluster.

The reference uses separate road-surface and road-marking style groups and
separately digitized detailed geometry. Public material describes generating
detail from panoramas and imagery; it is not evidence that a lane count alone
reproduces that geometry. A fixed numeric visibility threshold has not been
established. The zoom values below are proposed starting points for OM.

OpenStreetMap can supply lane counts, widths, turns, lane-change permissions,
placement, explicit marking geometry and lane-connectivity relations. Start the
prototype on major roads with explicit lane profiles rather than extrapolating
data availability from an arbitrary urban corridor.

The user supplied a concrete MKAD, kilometre 19 example: `oneway=yes`, `lanes=5`
and `width:lanes=3.5|3.5|3.75|3.75|3.75`. This gives five unequal lane widths,
totalling 18.25 m of lanes. Shoulders and separators are additional quantities,
not implicitly included in that sum. The lane-width values describe lanes,
not the thickness of the painted divider strokes. Together with road geometry
this is an appropriate first rendering fixture.

Use explicit per-lane widths first, including their directional variants. When
some entries are empty, preserve their positions and the explicit widths. If a
usable total width for these lanes is known, distribute the remaining width over
unknown lanes; do not blindly divide kerb-to-kerb width when parking, shoulders
or cycle lanes also occupy it. If no usable width remains available, use a
configurable estimated width, initially 3.5 m per ordinary motor lane. This is
a renderer policy, not a universal default guaranteed by the OSM tag scheme.
Store measured/derived/default provenance and validate array length against
the lane layout. Zero-width start/end transition entries require separate
handling because they can describe a lane forming or disappearing.

Coverage differs sharply between attributes and road classes. A small road-way audit in Moscow,
bounds south/west/north/east `55.744,37.558,55.752,37.578`, returned 154 ways of
motorway/trunk/primary/secondary/tertiary/residential/unclassified classes and
their principal links. Data timestamp: `2026-09-28T14:51:21Z`.

| Attribute present on sampled road ways | Count |
|---|---:|
| `lanes` | 134 |
| `lanes:forward` or `lanes:backward` | 6 |
| `width`, `width:carriageway` or `est_width` | 0 |
| `width:lanes*` | 0 |
| `turn:lanes*` | 18 |
| `change:lanes*` | 0 |
| `placement*` | 0 |
| `lane_markings` | 3 |

This is a local, way-count sample, not a citywide or length-weighted estimate.
One-way roads do not need a separate forward/backward lane count. Standalone
marking objects and connectivity relations were not counted by this query; a
separate count request timed out, so their absence cannot be asserted.

Useful inputs and their roles:

| Inputs | Normalized meaning |
|---|---|
| `lanes`, `lanes:forward/backward/both_ways` | Lane count by direction, with unknown distinct from zero. |
| `width:carriageway`, `width`, `est_width`, `width:lanes*` | Physical/estimated widths, normalized units and provenance. |
| `placement`, `placement:start/end` | Where the source line lies relative to the carriageway, including transitions. |
| `turn:lanes*`, `destination:lanes*` | Turn-arrow and destination semantics. |
| `change:lanes*`, access/bus lane tags | Lane usage and permissible changes; not an exact paint specification. |
| `lane_markings=no` | Suppress inferred painted lane dividers even if lane counts are known. |
| `road_marking`, `stroke`, `pattern`, `colour` | Explicit marking geometry/style when mapped. |
| `type=connectivity` relations | Connections between individual lanes across road segments. |
| `oneway`, bridge/tunnel/layer, road class | Orientation, carriageway separation and grade separation. |

Schema references: https://wiki.openstreetmap.org/wiki/Lanes,
https://wiki.openstreetmap.org/wiki/Key:placement,
https://wiki.openstreetmap.org/wiki/Key:road_marking,
https://wiki.openstreetmap.org/wiki/Relation:connectivity.

There must be a distinction between explicit geometry, a layout reconstructed
from attributes, and an unknown layout. Lane-change restrictions alone do not
uniquely determine paint color, single/double lines or exact marking position.
Unknown data must not silently become an authoritative double-solid line,
stop line, island, or lane connector. Estimated widths can produce a schematic
lane layout, with the estimate retained in the data model. Unsupported junctions
fall back to ordinary road rendering instead of drawing contradictory detail.

Extend the generator with an optional, versioned `road_details` section containing
compact numeric attributes, explicit marking objects and optional connectivity.
Keep references tied to that MWM's feature IDs and version. Preserve directional
meaning when geometry is reversed; test `oneway=-1`, left-hand traffic, shared
center lanes and per-direction lane arrays. Do not encode every lane count and
width as a separate classificator type. Existing official MWM files lack most of
these inputs, so adding only application code cannot supply them.

Generate the carriageway as a ground mesh in local metric coordinates. Prefer
explicit surface geometry when available; otherwise construct an offset ribbon
from the road line, lane widths, placement and shoulders. Use robust joins and
limit miters at tight bends. Changes from two to three lanes need tapered geometry,
not a step in stroke width. Separate opposing carriageways remain separate road
objects; lane counts must not be duplicated across both.

All paint uses the same road coordinate frame: lane boundaries, edge lines,
arrows, crossings, stop lines and hatching. Boundary offsets come from cumulative
lane widths. Exact marking objects override inferred decoration. For a first
prototype, use ordinary straight/curved sections; handle junction footprints,
merges, islands and connectors as a subsequent explicit stage. Incoming road
markings must end at the junction boundary unless a connector is known.

Current OM entry points are `generator/osm2meta.cpp`, the final-feature/section
builders, and `libs/drape_frontend/apply_feature_functors.cpp`. Existing
`LineShape` supports solid/dashed strokes, but `ExtractLineParams` currently
uses style widths in display-scaled pixels. A detailed carriageway needs a
metric geometry/width model rather than multiplying that pixel width by lane
count. A `RoadDetailBuilder` can reuse line/area rendering primitives while
supplying stable surface meshes and marking geometry.

Proposed visibility policy:

| Initial zoom gate | Detail |
|---|---|
| Through 16 | Existing overview roads. |
| 17 | Optional detailed carriageway outline where data is usable. |
| 18 | Lane separators if projected lane width is sufficient. |
| 19 and above | Turn arrows and small explicit markings where readable. |

Zoom is only a coarse gate. Decide actual visibility from projected lane/mark
size, perspective and available raster resolution. Start experiments around
6–8 screen pixels per lane for separators, with larger thresholds for arrows;
these are tuning candidates, not measured reference-app thresholds. Use gradual
fades and a deadband between entry/exit thresholds. The far part of a perspective
view should simplify before the near part. Main and cluster evaluate visibility
independently against their own camera, density and render scale.

Use stable ground-distance coordinates for dash phase. Clip after assigning
phase from the original feature, so panning and tile boundaries cannot restart
the pattern. Preserve local origins to avoid float jitter. At zoom transitions
there must be one effective owner for each marking: parent and child tile
geometry must not blend duplicate paint or flash while uploads complete.

Build geometry on background tile workers and cache immutable results by map
version, feature/patch and detail level. Reuse CPU geometry where both displays
request the same patch, while respecting their separate GPU contexts. Batch
surfaces and strokes; never allocate a render object for every dash. Reuse
existing pools and index buffers. Camera movement should update transforms and
visibility, not retessellate all roads on every frame.

Thin markings need explicit antialiasing. Compare analytic edge smoothing and
the existing MSAA configuration on the head unit. If road fill uses a reduced
render target, a native-resolution paint pass is possible, but only with correct
bridge/building occlusion: drawing all markings over the upscaled background
would expose roads below overpasses. Measure that pass against rendering all
detail together. The route line, traffic colors, camera sectors and labels need
coordinated order and opacity so they do not hide the road information.

Rendering lanes does not establish the vehicle's actual lane. Keep position
matching unchanged initially; lane guidance and lane-level vehicle localization
are separate features with additional data requirements.

Suggested delivery sequence:

1. Extend extraction/serialization and build a small test region. Validate
   missing, inconsistent and directional tags without changing existing maps.
2. Render simple carriageways with lane-count layouts and stable dividers in
   auto. Add smooth zoom transitions and verify both themes/displays.
3. Add explicit paint types, arrows and controlled lane-count transitions.
4. Add junction footprints, grade separation and verified lane connectors.
5. Only then connect detailed lane geometry to route guidance.

Acceptance should cover curved roads, two-to-three-lane transitions, tile seams,
opposite carriageways, bridges, roundabouts, missing data and `lane_markings=no`.
Replay a dense Moscow route on both real displays, including zoom sweeps near
each threshold. Record frame time, tile queues, CPU/GPU memory and draw calls
against the current build, with relevant render-scale/MSAA profiles.
