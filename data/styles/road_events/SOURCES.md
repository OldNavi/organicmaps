# Road-event symbols

Standard road-sign geometry is taken from the following SVG files on Wikimedia
Commons. Attribution and license declarations are available on each source page.
The map assets preserve the sign geometry, normalize the red/black palette, and
scale the viewport for map display. Rectangular settlement signs retain their
original aspect ratio.

| Map symbol | Standard sign | Source |
| --- | --- | --- |
| Dangerous bends | 1.12.1 | [SVG](https://commons.wikimedia.org/wiki/File:RU_road_sign_1.12.1.svg) |
| Intersection | 1.6 | [SVG](https://commons.wikimedia.org/wiki/File:RU_road_sign_1.6.svg) |
| Speed bump | 5.20 | [SVG](https://commons.wikimedia.org/wiki/File:RU_road_sign_5.20.svg) |
| Uneven road | 1.16 | [SVG](https://commons.wikimedia.org/wiki/File:RU_road_sign_1.16.svg) |
| Children | 1.23 | [SVG](https://commons.wikimedia.org/wiki/File:RU_road_sign_1.23.svg) |
| Other danger | 1.33 | [SVG](https://commons.wikimedia.org/wiki/File:RU_road_sign_1.33.svg) |
| No overtaking | 3.20 | [SVG](https://commons.wikimedia.org/wiki/File:RU_road_sign_3.20.svg) |
| Settlement begins | 5.23.2 | [SVG](https://commons.wikimedia.org/wiki/File:RU_road_sign_5.23.2.svg) |
| Settlement ends | 5.24.2 | [SVG](https://commons.wikimedia.org/wiki/File:RU_road_sign_5.24.2.svg) |
| Pedestrian crossing | 5.19.2 | [SVG](https://commons.wikimedia.org/wiki/File:RU_road_sign_5.19.2.svg) |

The generic camera, red-light, lane-control and mobile-camera symbols are converted
from the reference application's vector drawables. The generic camera uses
`road_alerts_camera_32.xml`, with the original geometry and colors in both themes.
The dummy variant uses the same camera geometry with a gray rim and silhouette.
Railway crossings and police use existing Organic Maps symbols.

Generate the additional atlas for all densities with:

```
python3 tools/python/generate_road_event_symbols.py --skin-generator build/skin_generator_tool
```

The generated atlas and its manifest are packaged only in `android/app/src/auto/assets/`.

Sign 5.20: Nikolaev_ec06ffa5, [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/).
The scaled/palette-adjusted version is distributed under the same license.

The video-surveillance silhouette uses the [CCTV icon](https://github.com/Templarian/MaterialDesign/blob/master/svg/cctv.svg)
from Pictogrammers, under Apache-2.0 (see `LICENSE-cctv.txt`). Its geometry is
preserved inside a neutral rounded-square badge.
