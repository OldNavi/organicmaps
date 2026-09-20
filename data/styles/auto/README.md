# Automotive palette

The default and vehicle families import these palettes after their original colors and
`*-roads.mapcss` after their geometry rules. Outdoors is unchanged. Rebuild with
`tools/unix/generate_drules.sh`; never edit drawing-rule binaries/text dumps by hand.

## Verified reference

Reference app: Yandex Auto (`ru.yandex.yandexnavi.auto`), version 2.3.3.5921.0.
Its `Ee/a` initialization selects `MapMode.DRIVING`. The APK's `res/raw/map_style.json`
only hides some road icons at zooms 17–18; it is not the base style.

The full style was read using root ADB on 2026-09-20 from:

```
/data/user/0/ru.yandex.yandexnavi.auto/cache/mapkit/images.sqlite
itemsru_RU: key=data_sourced_style (zlib-compressed JSON)
itemsru_RU: key=v:data_sourced_style, value=31069
```

Decoded JSON: 3,487,878 bytes, 495 layers; SHA-256:
`18b23a1ad0f49360603734279ea18acbf4a82508415a8b0534db20d4e6f54c94`.
The native library contains the style loader/decoder and `/style3`/`/styles2` paths.
The complete palette was obtained from the cache, not from literal colors found in the ELF.
This is the installed app's cached style revision, which can change independently of its APK.

Only color facts and their OM mapping are recorded here. The cache, original stylesheet,
Yandex fonts, icons, models and tiles are not bundled in this repository.

## Mapping

Each palette variable records its source `library.colors` identifier. Both day and night
values come from the same entry. The source's `map-type=driving` layer overrides take
precedence over its ordinary map defaults.

| OM role | Reference in the DRIVING style |
| --- | --- |
| Land/background | `land-background`, `ground-99` |
| Water/rivers | `water-fill` / `water-river`, zoom 16 color `.water-fill__btm_water-82_12` |
| Forest/parks | `flora-wood`, zoom 16 color `flora-94` |
| Grass/agricultural areas | `flora-96` |
| Buildings | `bld-fill-0`, `building-94`; borders adapted using `building-90` |
| Industrial/airport | `industrial-97` |
| Hospital/education | `hospital-98` / `education-98` |
| Parking | `.parking-area` |
| Motorway/trunk | `road-sd-1` (Yandex classes 1 and 2 share the color function) |
| Primary/secondary/tertiary | `road-sd-3` / `road-sd-4` / `road-sd-5` |
| Residential/service/unclassified | `road-sd-6` |
| Road text | `.label-road__navi` |
| Address/POI/metro | `.label-address` / `.poi-label__navi` / `.label-metro__navi` |

Road colors are sampled from the source's linear zoom interpolation at each integer zoom
6–19, rounded to 8-bit RGB, and clamped to the zoom-19 result above that. They also cover
links and tunnels. OM continues to supply widths, casing, opacity, priorities and visibility.
Other map colors use representative detailed-map stops, mainly zoom 16–18. Translucent
palette entries (address/country/water text) are composited against `ground-99` for OM's
opaque color variables. Agricultural areas, barriers and building borders use the nearest
palette roles; these are explicit adaptations rather than equivalent Yandex feature classes.

The green route convention is retained using the APK's exact free-flow color `#82EA0E`;
its green outline uses the manoeuvre-outline color `#308B0A`. This is an OM adaptation:
Yandex's base selected-route stroke is blue `#177EE6`, with a separate jam-color overlay.
Traffic color constants use the APK's `mapkit_automotive_jam_*` resources (free/light/hard/
very-hard/blocked/unknown). This changes colors only and adds no live traffic service.
The existing yellow OM position arrow remains an approximation, not Yandex's 3D model.

Geometry, labels, POI symbols and fonts remain Organic Maps/OpenStreetMap. Ski difficulty
and contour colors retain OM semantics. The result adapts the automotive color scheme;
it does not reproduce Yandex's HD road surfaces, relief, continuous zoom interpolation,
label placement or proprietary map data.
