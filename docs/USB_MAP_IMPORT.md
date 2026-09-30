# USB map import (Android auto flavor)

Put regional `.mwm` files directly in `omdata/maps` at the root of a removable
volume. The automotive app scans mounted volumes when its map screen opens and
when Android reports a new mount. If the map screen is in the background, the
import dialog and copying start when it becomes active. On Android 11 and later,
the first scan can request the system's all-files access permission. Other
flavors do not request this permission or enable automatic scanning.

The importer reads the MWM `version` section and compares its full Unix timestamp
with the corresponding map registered on the head unit. The filename identifies
the region. Missing maps and strictly newer timestamps are imported; equal and
older timestamps are skipped, regardless of filesystem modification times.
Among duplicate filenames on several volumes, the newest timestamp wins.

The current MWM container format (`v11`) is supported. The road-detail sections
are optional; normal maps without them can be imported and registered too.
Two custom rebuilds with identical embedded timestamps are indistinguishable
for update ordering. Assign a new `--planet_version` when publishing a rebuild.

The dialog lists the maps and destination and shows byte progress for copying
and checksum verification. A foreground service continues copying if the screen
is closed. Files go to the storage directory currently selected in Settings,
under the date derived from their embedded timestamp. The copy is first written
to a sibling `.usb-copy` file and synced. SHA-256 verifies the copied bytes.
Cancelled or failed copies leave existing maps intact. Incomplete temporary
files from an interrupted process are replaced on the next attempt.

Map activation waits for routing and map downloads to become idle. Native map
registration is suspended while completed files are atomically renamed into
place, then reloaded. Each completed file remains installed if a later file in
the batch fails. Cancelling before activation removes the prepared copies.
Changing the configured storage during copying also cancels activation.

An internal `.mwm.imported` sidecar marks imported files. It is moved together
with the map if the user changes storage and is removed when the map is deleted.
This allows imports newer than the built-in catalogue to survive restarts,
without changing the official catalogue or its download URLs. Do not replace
`countries.json` with a locally generated release date: the official servers
may have no `World` or `WorldCoasts` files under that date.

Tests:

- `UsbMapFilesTest`: timestamp selection, duplicates, malformed input, path
  escapes, interrupted copying, atomic replacement and selected destination.
- `LocalCountryFile_ImportedMapNewerThanCatalogue`: discovery of marked imports.
- `StorageTest_ImportedMapsKeepOfficialCatalogue`: local import status, deletion
  and retention of the official catalogue version.
- `UsbMapImportTest`: a device test with a real MWM lacking road-detail sections;
  installs it, skips an equal version and replaces a newer same-day version.
  Supply `usbTestRoot/omdata/maps/UsbImportTest.mwm` as an instrumentation fixture.
  This tests the mount-event handler; physical USB mounting is a separate check.
