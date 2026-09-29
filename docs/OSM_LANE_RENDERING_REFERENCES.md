# Открытые реализации дорожных полос из OpenStreetMap

Проверено по исходному коду 29 сентября 2026 года. Проекты не запускались;
качество конкретных московских развязок в них не проверялось.

## Что стоит использовать в Organic Maps

**OSM2World** — наиболее компактный пример сопоставления полос при слиянии и
разделении. **osm2streets/A-B Street** — полезный образец модели дороги и
согласования геометрии с её уровнем. **SUMO** — дополнительный источник правил
связности на C++. Ни один из проверенных проектов не решает одновременно все
случаи `placement`, слияний, колец, выделенных полос и подписей под мостами.

| Проект | Практически полезная реализация | Ограничения |
|---|---|---|
| osm2streets / A-B Street | Упорядоченные полосы с шириной, направлением и типом; полигоны полос; отдельная обработка съездов | Меняющийся placement реализован не полностью; геометрия съезда не доказывает соответствие отдельных полос |
| OSM2World | Сопоставление входящих/исходящих полос с двух краёв, соединяющие поверхности | Ограничения для односторонних дорог; исчезающие полосы отмечены TODO |
| SUMO | Явные ветки 1+1→2 и 3→2+1; поперечные смещения полос и кривые внутри узлов | Часть связности выводится эвристически; лицензия отличается от Apache/MIT |
| JOSM Lanes | Интерполяция placement и ширины между началом и концом way | Редакторский прототип; кольца исключены из обработки перекрёстков; GPL v2 |
| Map Machine | Две кривые Безье для перехода между сегментами разной ширины | Узлы с более чем двумя дорогами пропускаются |

## osm2streets / A-B Street

`get_lane_specs_ltr` формирует полосы через `muv-osm`; тип Bus и автобусный доступ
представлены в модели. `to_lane_polygons_geojson` строит отдельные полигоны и
передаёт им layer. Специальный алгоритм `on_off_ramp` ограничивает слишком большие
узлы при почти параллельных съездах. Это решение формы полотна, а не гарантия
связности 3→2+1.

- [Парсер полос](https://github.com/a-b-street/osm2streets/blob/fc119c47dac567d030c6ce7c24a48896f58ed906/osm2lanes/src/algorithm.rs#L22)
- [Полигоны полос](https://github.com/a-b-street/osm2streets/blob/fc119c47dac567d030c6ce7c24a48896f58ed906/osm2streets/src/render/mod.rs#L110)
- [Геометрия съезда](https://github.com/a-b-street/osm2streets/blob/fc119c47dac567d030c6ce7c24a48896f58ed906/osm2streets/src/geometry/on_off_ramp.rs#L8)

`placement:start/end` парсится, но `get_untrimmed_center_line` использует только
начало. `placement=transition` пока трактуется как центральное положение:
[реализация](https://github.com/a-b-street/osm2streets/blob/fc119c47dac567d030c6ce7c24a48896f58ed906/osm2streets/src/road.rs#L170).

У A-B Street название входит в пакет геометрии дороги. Дороги, полосы и
перекрёстки сортируются по zorder. Из этого порядка следует возможность закрыть
название нижней дороги верхним полотном; это вывод из кода, без визуальной
проверки. В OM дополнительно важно сохранять читаемость целых слов.

- [DrawRoad](https://github.com/a-b-street/abstreet/blob/0964f29315820c91b171b585eb51e300164e9197/map_gui/src/render/road.rs#L37)
- [Сортировка по уровню](https://github.com/a-b-street/abstreet/blob/0964f29315820c91b171b585eb51e300164e9197/map_gui/src/render/map.rs#L389)
- Лицензии: [osm2streets Apache-2.0](https://github.com/a-b-street/osm2streets/blob/main/LICENSE), [A-B Street Apache-2.0](https://github.com/a-b-street/abstreet/blob/main/LICENSE).

## OSM2World

`RoadModule.java` содержит полосовые поверхности, направления, индивидуальную
ширину и разделители. `LaneLayout.setCalculatedValues` распределяет неизвестные
ширины. `buildLaneConnections_allOneway` собирает полосы входящих и исходящих
дорог слева направо; `findMatchingLanes` сопоставляет типы с двух краёв.

Это полезная основа для 1+1→2 и 3→2+1. Входящие и исходящие дороги должны
образовывать непрерывные группы; предусмотрены ограничения на тротуары.
Соединение краёв в `buildLaneConnection` прямолинейное, исчезновение полос не
закончено. Полноценная обработка placement и bus/psv-полос в этом модуле не
подтверждена; bus_bay означает автобусный карман.

- [Соединение полос](https://github.com/tordanik/OSM2World/blob/8ec26a9ea426444a4f7882cf0cfcab432c876ae5/core/src/main/java/org/osm2world/world/modules/RoadModule.java#L296)
- [Уровни мостов и тоннелей](https://github.com/tordanik/OSM2World/blob/8ec26a9ea426444a4f7882cf0cfcab432c876ae5/core/src/main/java/org/osm2world/world/network/AbstractNetworkWaySegmentWorldObject.java#L305)
- Текущая лицензия: [MIT](https://github.com/tordanik/OSM2World/blob/master/LICENSE.txt).

## SUMO

`NBNode::computeLanes2Lanes` отдельно обрабатывает две входящие дороги и одну
исходящую с суммой полос, а также одну входящую и две исходящие с равным либо
на одну большим суммарным числом полос. Сначала упорядочиваются дороги, затем
соединяются диапазоны полос. Есть отдельная обработка zipper.

`NBEdge::computeLaneShapes` рассчитывает смещения с разными ширинами;
`computeInternalLaneShape` и `bezierControlPoints` строят внутренние кривые.
Импортёр поддерживает width:lanes, bus:lanes, psv:lanes, кольца и относительную
высоту. Placement ограничен фиксированным положением явно односторонней дороги;
start/end/transition не реализованы. Связность часто эвристическая.

- [Правила соединения](https://github.com/eclipse-sumo/sumo/blob/b31af4d5e237e362a553dedfbbdad1e92249b3a3/src/netbuild/NBNode.cpp#L1243)
- [Геометрия полос](https://github.com/eclipse-sumo/sumo/blob/b31af4d5e237e362a553dedfbbdad1e92249b3a3/src/netbuild/NBEdge.cpp#L2275)
- [Кривые узлов](https://github.com/eclipse-sumo/sumo/blob/b31af4d5e237e362a553dedfbbdad1e92249b3a3/src/netbuild/NBNode.cpp#L596)
- [OSM-импортёр](https://github.com/eclipse-sumo/sumo/blob/b31af4d5e237e362a553dedfbbdad1e92249b3a3/src/netimport/NIImporter_OpenStreetMap.cpp#L830)
- [Документация импорта](https://sumo.dlr.de/docs/Networks/Import/OpenStreetMap.html)
- Лицензия: [EPL-2.0 OR GPL-2.0-or-later](https://github.com/eclipse-sumo/sumo/blob/main/NOTICE.md). Для OM предпочтительна самостоятельная реализация выбранных правил.

## JOSM Lanes и Map Machine

JOSM Lanes интерполирует смещение между placement:start/end по расстоянию вдоль
way; читает также width:lanes:*:start/end. Полноценные слияния 3→2+1 и автобусная
семантика не подтверждены, кольца явно исключены из intersection renderer.

- [Placement](https://github.com/BjornRasmussen/Lanes/blob/7468213eaa30ea7887b5abf2ed40ac1300f3329f/src/org/openstreetmap/josm/plugins/lanes/RoadRendererMarked.java#L274)
- [Интерполяция](https://github.com/BjornRasmussen/Lanes/blob/7468213eaa30ea7887b5abf2ed40ac1300f3329f/src/org/openstreetmap/josm/plugins/lanes/UtilsSpatial.java#L283)
- [Исключение колец](https://github.com/BjornRasmussen/Lanes/blob/7468213eaa30ea7887b5abf2ed40ac1300f3329f/src/org/openstreetmap/josm/plugins/lanes/UtilsSpatial.java#L72)
- Лицензия по [README](https://github.com/BjornRasmussen/Lanes/blob/7468213eaa30ea7887b5abf2ed40ac1300f3329f/README): GPL v2. Это источник идеи, не готовый код для включения в Apache-проект.

Map Machine строит ComplexConnector двумя кубическими кривыми Безье, в том числе
для placement=transition. Узлы с более чем двумя дорогами пропускаются. Подписи
рисуются после всех дорожных слоёв, поэтому этот порядок не подходит для нашего
случая с надписью под мостом.

- [ComplexConnector](https://github.com/enzet/map-machine/blob/eab10c1ec9bcd0a78fa6db75d02e6219fcea562c/map_machine/feature/road.py#L780)
- [Порядок отрисовки](https://github.com/enzet/map-machine/blob/eab10c1ec9bcd0a78fa6db75d02e6219fcea562c/map_machine/feature/road.py#L992)
- [Код MIT](https://github.com/enzet/map-machine/blob/main/LICENSE); иконки имеют отдельную лицензию, указанную в [README](https://github.com/enzet/map-machine).

## Применение

Приоритет — согласованная модель полос и узла: связанные диапазоны полос,
непрерывное поперечное смещение, общее полотно и разметка на той же геометрии.
Сопоставление полос следует отделять от построения кривых и отличать явные
соединения от эвристик. Одни числа 3, 2 и 1 не доказывают, куда продолжается
каждая полоса.

Для подписей область закрытия должна включать всё верхнее полотно, в том числе
поверхности примыканий, которые рисуются отдельным проходом. Не следует
использовать оставшуюся после вырезания примыканий центральную ленту как полную
маску дороги. Это уже подтверждено отдельным случаем Рязанского проспекта в OM.

Прямого копирования кода этих проектов в текущем изменении нет.
