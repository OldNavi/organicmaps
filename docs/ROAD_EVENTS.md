# Automotive road events

Available only in the Android `auto` flavor. Enable **Road events** in general
settings or in **Map styles and layers**. The provider has its own settings
fragment with account controls and an imported-country list showing update dates. Two visibility menus control individual types on the regular map and during guidance.
A shared Off / Important only / All setting controls road-event, camera and overspeed
alerts, with voice or signal delivery. Disabling the feature removes the layer and external speed-limit
overrides without deleting downloaded countries.

## Sources and importers

`RoadDataProvider` owns authentication, the country list, and export requests.
Every provider supplies its own `RoadEventImporter`. The first provider is
`openspeedcam.net`. It uses direct HTTPS requests, without a browser or WebView.
Its session cookies stay in memory and respect their domain, path, and expiry.
Credentials are encrypted per provider with an Android Keystore key in the
no-backup directory. Downloads restore an expired session using those credentials;
signing out removes the stored credentials.

Each export requests one country. The initial suggestion comes from the location
and offline map-region metadata; manual selection takes precedence. The local
file picker also requires a country. A local file must contain that country's
data. Downloading or importing another country preserves existing countries.

`OpenSpeedCamImporter` owns the Windows-1251 decoder, source field meanings,
subtype labels, endpoint roles, and azimuth conversion. It streams normalized
batches into SQLite. Source labels never enter rendering or warning logic.
`RoadEventKind` contains stable numeric IDs shared with the native enum; localized
names come from application resources through `RoadEventLabels`.

SQLite replacement is a transaction scoped to `(provider, country)`. Malformed or
interrupted imports roll back both event rows and import metadata. A valid empty
export replaces that country's data with an empty dataset. The same source ID
can describe multiple directional records or endpoint roles, so it is not a row
primary key. The database is stored in the application's no-backup directory.

## Native index and rendering

Location changes determine the current country and countries within 30 km. Only
their available SQLite records are loaded into an immutable C++ spatial index.
Before fresh GNSS arrives, the visual layer can use the last indexed country or
the single imported country. This does not permit warnings from stale coordinates.
Preparation happens on one worker; publication swaps the shared snapshot. Network
requests have their own worker and do not block location processing. The country
lookup table is generated from map metadata by
`tools/python/generate_road_event_countries.py`.

The primary map and each cluster renderer query the shared index independently.
Their viewport layers retain an expanded rectangle and rebuild only after moving
outside it, changing zoom, or changing the data/settings revision. Layers query the entire expanded viewport without a record cap. Nearby camera
symbols with the same direction (within 10 degrees) are thinned in screen-sized
neighborhoods. Speed cameras take priority over other enforcement cameras and
dummies. Opposite and unknown directions remain distinct. This affects only
rendering, not the warning index or provider data. Minimum zooms are configured per type in the automotive
asset `android/app/src/auto/assets/road_event_display.json`, read once at startup.
Speed cameras start at zoom 12; video surveillance, dummy cameras and bumps
start at 16. Video surveillance uses its own CCTV symbol and never draws a sector. The values
are inclusive integer zoom levels (1–20), separate from the two per-type visibility sets.
Settlement-end signs remain hidden regardless of their configured threshold. Icons have 14/18/22-pixel variants for zooms 13/15/17,
adapted to each display's density. Settlement-end signs are hidden only visually;
their records still participate in speed-limit handling. Road events paint over
map POIs and road labels without suppressing the underlying labels.
Video-surveillance symbols are gray CCTV badges, start at zoom 16 by default,
and never contribute a camera gradient or `/speed_camera` entry.

From zoom 15, directional cameras also show their approach sectors as light-orange,
translucent ground fills with a radial fade. These use the source warning distance
(capped at the existing 2 km look-ahead horizon), direction and angle. Two-way
cameras get two sectors; missing provider direction/angle is not guessed. Fills use the
same immutable source and active type visibility as icons. They are uploaded as one
complete, untiled snapshot and replaced atomically: old/new zoom tiles must not
blend the same translucent sector twice. A fixed local origin keeps GPU coordinate
precision; map zoom changes only its transform. The snapshot is recached on data,
visibility, style or graphics-context changes. The gradient texture is generated
in the automotive atlas, not rebuilt each frame.

The **Camera warning areas** switch controls fills independently of icons and alerts.
With fresh location data, guidance uses the selected route's directed segments;
without guidance, matching follows the current street in both directions and stops
at ambiguous forks. Same-name/ref continuations can cross feature boundaries.
Cameras on an opposing or nearby parallel carriageway are excluded by road matching.
Selection searches a circle of radius 2 km (diameter 4 km) and is reused until moving 100 m,
changing road, route or data. A spatial prefilter limits expensive graph lookups.
Both displays consume the same immutable selection, with no graph I/O in rendering.
Without guidance, a brief ambiguous road match retains the last selection for up
to 3 seconds and 100 m while heading stays within 30 degrees. This visual grace
period is measured from the last successful match, not extended by missing matches.
A confirmed road change, discontinuity or source replacement takes effect immediately;
camera warnings still require a current match.
GPS expiry clears it; results from before expiry, rerouting or replacement are rejected.
Map browsing away from the vehicle retains camera icons without distant coverage fills.

In the auto flavor, MWM speed-camera sections are read once per encountered map on
the file thread and published as a shared immutable index. Road geometry supplies
the bearing; a stored forward/backward hint selects the approach, while unknown
or bidirectional cameras use both approaches. Their conventional sector uses
500 m and a 15-degree half-angle, not a measured detection range. Map updates and
removals invalidate this cache. Reading and indexing never run in a render frame.

Both sources use the same camera symbol and speed badge (in the selected units).
An imported speed camera takes priority over an MWM camera within 50 m with a
matching direction (10-degree tolerance; bidirectional cameras compare road axes).
This merge affects presentation only. Camera POIs without a routing-section entry
retain a point symbol; auto atlas aliases give them the same camera artwork.
Other flavors retain the stock symbols and camera rendering.

Cluster symbol visibility and size use camera zoom, independent of the coarser
terrain tiles selected in perspective. Pending mark invalidations survive viewport
updates until the backend consumes them. Road-label direction retains its previous
orientation within five degrees of vertical, and switches beyond that deadband.

External mark IDs and the group are reserved separately from BookmarkManager-owned layers.

## Matching, limits, and warnings

The road worker checks travel direction and the matched carriageway; ambiguous
parallel roads are rejected. Near-straight connected feature fragments represent
one approach at a segment boundary. Camera coordinates may be on roadside objects:
association tolerates the source sector's lateral extent, capped at 100 m, while
warnings still require entering that sector. The camera can look obliquely across
the road: its optical sector constrains vehicle positions, not the road tangent.
Traffic travelling away from that sector is still rejected. The nearest compatible road must be
the travelled road (or its directly connected continuation); proximity alone does
not transfer a camera between parallel roads. Without a known continuation it stops at a junction,
rather than guessing the driver's next road. A posted speed limit is applied only
after passing the sign on the matched road. Camera enforcement speeds and hazard
advisory speeds do not become road limits. Settlement baselines do not raise a
known lower limit. Overrides are cleared on lost continuity, a road-feature
change, disabling the feature, or replacing the indexed data.

The resulting limit is shared by the main speed display, overspeed controller,
and navigation provider. Automotive road alerts have one shared level: Off,
Important only, or All; output can be speech or a warning sound on navigation
usage. Important includes speed/lane/intersection cameras, children and bumps,
as well as overspeed. In Important mode, known speed-camera limits use the common
overspeed offset; All announces them regardless of speed. A bump requires speed
strictly greater than its source limit plus 10 km/h in either mode. Warnings need
fresh movement and a matching approach sector. Hidden types are also silent.

Imported and built-in cameras share one warning owner, preventing duplicate
native speech/beeps. A bounded list of matching events prevents an already spoken
or ineligible nearest event from blocking the next warning. Repeated alerts are
suppressed for the current approach and rearmed after leaving it.

Two independent per-type visibility menus, Show on the map and Show during
navigation, switch automatically with guidance. Existing category preferences
are migrated into both sets once. Hiding the entire layer retains its current
index so re-enabling redraws immediately; an import updates even a hidden cached
index. Provider management is grouped under Data providers.
Average-speed endpoints retain their distinct types and source identity; this
layer does not calculate section-average speed or change route costs/ETA.

`content://organicmaps.auto.navi/road_events` exposes the nearest matched event:
`id`, numeric `kind`, `distance` (metres), `speed` (m/s), `lat`, `lon`, localized
`name`. `/guidance` uses the resolved road limit; `/speed_camera` includes matched
external stationary/mobile speed cameras and average-speed endpoints. Dummies,
police posts, red-light/lane enforcement and general video surveillance are
excluded before choosing the nearest speed camera; other event types remain available through `/road_events`.
Map visibility and zoom thresholds do not filter provider output. Expired fixes
clear the published data.

When an enabled, configured source has no dataset for the current country, the
main map offers to open its download settings. It does not start a network export
without user action and does not repeatedly prompt on every GNSS fix.

`/road_events_status` exposes enabled state, indexed record count and data revision
for diagnostics. Map symbols are generated from SVG sources documented in
`data/styles/road_events/SOURCES.md`.

## Selecting an event

In the automotive flavor, tapping a visible sign opens the existing map-object
place page. Its localized title, coordinates and normal map actions are retained;
an additional section shows the source, import date and speed when provided.
Hazard advisory speeds are labeled separately from speed limits. Selection does
not read SQLite or use the network: its metadata comes from the rendered index.
Selecting a camera temporarily shows its warning area on the main map, including
when automatic fills are hidden or the camera is outside the current road/route.
The preview also works below zoom 15 and disappears when the place page closes;
it is not copied to the cluster. Selection does not pan or zoom the map.
A selected automatic area is drawn only once.
The source action opens the provider's record by its source ID; sources without
a record link retain the coordinate-sharing action.

Mark IDs include the index revision, so a delayed tap cannot select a different
record after an import or a visibility change. The selected snapshot is carried
through native place-page refreshes and Android parcel restoration.

Camera speed badges avoid other event icons and other badges in screen space.
If there is no room, the badge text and background are hidden together; camera
symbols remain visible and the limit is still available in their place page.

Icons remain above POIs and road labels without suppressing them. Their hit-test
handles do not participate in displacement or mutate render indices each frame.
The dummy-camera type uses the camera silhouette with a gray rim and gray camera;
it does not draw an approach sector.

The automotive atlas is generated at
`android/app/src/auto/assets/symbols/<density>/<theme>/road-events.png`, with a
matching XML index. Generic and dummy cameras use separate named symbols.

## Background updates

Each provider has an automatic-update switch (off initially) and a positive integer
interval in days (default seven). WorkManager persists network-constrained checks:
a periodic check every 12 hours, plus a check on a fresh country change and at most
once per hour while location updates continue. The configured interval is the
minimum data age, measured from SQLite's last successful import, not from job start.

Only existing imports of the current country are updated. The country must have
been observed from a fresh GNSS fix within 30 minutes; imported countries and the
visual fallback index are never used to guess it. An unknown current country does
not fall back to nearby regions. The updater does not wake GNSS or open an activity.

Manual and automatic imports share one busy owner and the same provider, network
executor, database executor and importer. Credentials remain in the existing
encrypted store and are never included in work payloads. Expired sessions are
reauthenticated; rejected credentials pause automatic jobs until a successful login.
Network failures use bounded exponential retries.

Cancellation, disabled updates, a changed/expired country observation, or a raised
interval abort the pending update. Cancellation during parsing rolls back the
transaction; the previous data stays usable. Successful import publishes the native
index only if the core is initialized, otherwise the next map startup loads it.
