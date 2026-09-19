# Ручная установка MOCK_LOCATION на ROX

## Просто задать место: без карты и без запуска тестов

В debug-сборке есть команды провайдера. Android при необходимости поднимет фоновый процесс
приложения, но Activity, маршрут и instrumentation-тесты не запускаются. Достаточно основного
debug APK; APK тестов для этих команд не нужен.

```sh
adb shell appops set app.organicmaps.auto.debug MOCK_LOCATION allow

adb shell content call --uri content://organicmaps.auto.navi \
  --method set_mock_location \
  --extra latitude:s:55.7522 --extra longitude:s:37.6156
```

Повторите последнюю команду с другой парой координат, чтобы переместить точку.
Провайдер обновляет timestamp той же позиции раз в секунду, пока жив процесс debug-приложения.
Можно дополнительно указать `--extra speed:s:15` (м/с) и `--extra bearing:s:90` (градусы).
По умолчанию скорость и курс равны нулю. Это системный тестовый GPS, доступный и другим
потребителям LocationManager; сама подмена не открывает Organic Maps.

Вернуть обычный GPS нужно **до** отзыва mock-разрешения:

```sh
adb shell content call --uri content://organicmaps.auto.navi --method clear_mock_location
adb shell appops set app.organicmaps.auto.debug MOCK_LOCATION default
```

Эти команды недоступны в release. В debug они разрешены только adb shell/root и UID самого
приложения. После остановки процесса обновления прекращаются; для снятия подмены всё равно
выполните `clear_mock_location`. Если исходный режим app-op был не `default`, восстановите его.

## Автоматическая проверка курсора (по желанию)

На проверенном ГУ команда `cmd location` не поддерживает создание тестовых провайдеров.
Координаты задаёт тестовый APK через обычный Android `LocationManager`.

Нужны debug APK и APK тестов из этой ветки:

- `android/app/build/outputs/apk/auto/debug/OrganicMaps-26091802-auto-debug.apk`
- `android/app/build/outputs/apk/androidTest/auto/debug/OrganicMaps-26091802-auto-debug-androidTest.apk`

Оба пакета уже установлены на проверенном ГУ. При самостоятельной установке:

```sh
adb install -r -g android/app/build/outputs/apk/auto/debug/OrganicMaps-26091802-auto-debug.apk
adb install -r -g android/app/build/outputs/apk/androidTest/auto/debug/OrganicMaps-26091802-auto-debug-androidTest.apk
```

Геолокация Android должна быть включена. Если устройств несколько, добавляйте
`-s 000000001f68471d` после `adb` (или серийный номер своего устройства).

## Запуск

Сначала запомните прежний режим разрешения:

```sh
adb shell appops get app.organicmaps.auto.debug MOCK_LOCATION
adb shell appops set app.organicmaps.auto.debug MOCK_LOCATION allow

adb shell am instrument -w -r \
  -e class 'app.organicmaps.sdk.ClusterRenderingTest#positionMarkerStaysFixedWhileTheMapFollowsLocation' \
  app.organicmaps.auto.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Тест создаёт два виртуальных экрана, включает тестовый провайдер `gps`, затем примерно
по 2,5 секунды подаёт две позиции с интервалом 100 мс:

1. `55.7522, 37.6156`;
2. `55.7552, 37.6196`.

Точность — 2 метра, скорость — 10 м/с, направление — 0°. Во время проверки эти координаты
доступны через системный GPS-провайдер. Проверка подтверждает, что координаты дошли до
`LocationHelper`, и сравнивает положение жёлтого курсора на отрисованных кадрах. Изображения
сохраняются в `files/cluster-marker-first.png` и `files/cluster-marker-second.png` debug-приложения.
Окно QNX-приборки тест не заменяет. Успех обозначается `OK (1 test)`.

## Восстановление

Тест удаляет свой провайдер в `close()`. После ошибки/прерывания дополнительно выполните
отдельную очистку **до** отзыва mock-разрешения:

```sh
adb shell am instrument -w -r \
  -e class 'app.organicmaps.sdk.ClusterRenderingTest#restoreMockGpsProvider' \
  app.organicmaps.auto.debug.test/androidx.test.runner.AndroidJUnitRunner

adb shell appops set app.organicmaps.auto.debug MOCK_LOCATION default
adb shell appops get app.organicmaps.auto.debug MOCK_LOCATION
```

На проверенном ГУ исходный режим был `default` (`deny`). Если до теста у вас был другой
режим, восстановите его вместо `default`. Код завершения `am instrument` сам по себе
не доказывает успех: смотрите строку `OK`, `FAILURES` или `Process crashed`.

Координаты и длительность сценария меняются в
`android/app/src/androidTest/java/app/organicmaps/sdk/ClusterRenderingTest.java`.
Обёртка `ClusterTestLocation` создаёт `Location`, задаёт `time`, `elapsedRealtimeNanos`,
точность/скорость/курс и вызывает `LocationManager.setTestProviderLocation()`.
После изменения пересоберите `:app:assembleGoogleDebugAndroidTest` и установите APK тестов заново.
