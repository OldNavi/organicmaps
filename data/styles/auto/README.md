# Automotive palette

The default and vehicle style families import these palettes after their original colors.
The outdoors family is unchanged. Roads, labels, geometry and POI remain Organic Maps data;
no Yandex tiles, glyphs, icons or binary styles are bundled.

The design reference is Yandex Auto used by RoxPremium. Night colors are sampled from local
1920x720 instrument-cluster captures (`rox_yandex_zoom/recreate-before.png` and
`zoom_dropdown_stage/route-cluster-01.png`): background #232C3F, buildings #303B4F,
minor roads #495B84, major roads #6C7DA3, vegetation #19423E, labels #D7E1FC/#B7C0D2.
The green route and yellow existing Organic Maps arrow reproduce the visual hierarchy.
The light palette is an approximation; it has not been matched to a daytime capture.

MapKit's [JSON style contract](https://yandex.ru/maps-api/docs/mapkit/style.html) describes
customizations to its renderer, not an export of the built-in Yandex Auto style. Correspondence:

| MapKit category | MapCSS variables |
| --- | --- |
| landscape / land | background |
| landscape / vegetation / park | forest, green0..5 |
| water | water, river |
| structure / building | building0, building1, building_border0 |
| road / geometry.fill | trunk*, primary*, secondary0, residential |
| label.text.fill / outline | label_*, label_halo_* |

This is a native MapCSS adaptation, not a runtime JSON importer. Rebuild with
`tools/unix/generate_drules.sh`; do not edit generated drawing rules by hand.
