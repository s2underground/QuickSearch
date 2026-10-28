QuickSearch
_________________________________________________________________
PURPOSE AND CAPABILITIES

A search bar for ATAK. One text box finds anything on a cluttered map and
jumps the map to any coordinate you paste.

- Searches markers, shapes, routes and contacts by name/callsign, remarks,
  icon/type and Overlay Manager group, ranked by match quality, then distance.
- Searches features in imported files (KML/KMZ, shapefiles, GPX, GeoJSON) by
  name, description (HTML removed), KMZ ExtendedData and file/folder name.
- Recognizes pasted coordinates (MGRS, UTM, decimal degrees, degrees-minutes,
  degrees-minutes-seconds, Google/Apple/OSM map links) and moves the map
  there, optionally dropping a spot marker.
- Tapping a result flies the map to it; long-press also opens its radial menu.

_________________________________________________________________
STATUS

Released, version 1.0, for ATAK-CIV 5.6.0. Developed and tested on ATAK-CIV
5.5.1.8 (developer build) on an Android tablet; built with the 5.6.0 plugin
template (Gradle 8.14.3, Android Gradle Plugin 8.13.0).

_________________________________________________________________
POINT OF CONTACTS

S2Underground: s2underground@protonmail.com

_________________________________________________________________
PORTS REQUIRED

None. QuickSearch makes no network connections.

_________________________________________________________________
EQUIPMENT REQUIRED

None.

_________________________________________________________________
EQUIPMENT SUPPORTED

Any Android phone or tablet running ATAK-CIV 5.6.0.

_________________________________________________________________
COMPILATION

Standard ATAK plugin build from the plugin template, no extra dependencies:

    ./gradlew assembleCivRelease

Unit tests (plain JVM, no device): `./gradlew testCivDebugUnitTest`.
Lint: `./gradlew lintCivRelease` (0 errors; the remaining warnings are unused
resources inherited from the plugin template).

_________________________________________________________________
DEVELOPER NOTES

- No permissions beyond ATAK's own; no native libraries; no third-party
  libraries.
- Uses only public plugin SDK APIs, none deprecated: `MapView.getRootGroup()`
  to walk map items, `MapTouchController.goTo()` to fly to an item (the call
  the Overlay Manager uses), the File Overlays' `FeatureDataStore2` (read with
  `queryFeatures`, watched with a content-changed listener) and
  `FeatureHierarchyListItem.goTo()` for imported features,
  ATAK's `MGRSPoint`/`UTMPoint` decoders, `ATAKUtilities.scaleToFit()`,
  `CameraController.Programmatic.panTo()` and `PlacePointTool.MarkerCreator`.
- Map items are searched on the UI thread (fast); imported-file features are
  indexed and searched on one background thread, and the index is rebuilt when
  the feature database reports a change.
- The Android version code is pinned in `gradle.properties`
  (`takStaticVersion`, 1.0 = 10000) so builds don't depend on git being
  present; bump it with every release. The build name still carries the git
  commit ID when built from a checkout.

---

# User guide

## Installing

Download the APK for your ATAK version from this project's Releases page and
open it on the device (or `adb install` it). Then open ATAK. It asks
"Load plugin: QuickSearch?"; tap OK. If it doesn't ask, open the Plugins
(Package Manager) screen from ATAK's menu, tap QuickSearch, then **Load**.

The QuickSearch button sits with the other tools in ATAK's menu; add it to the
main toolbar through ATAK's toolbar customization for one-tap access. Tapping
the button again closes the panel.

Release APKs are signed through TAK.gov's third-party pipeline and load in the
regular ATAK-CIV from the Play Store or TAK.gov. A build you make yourself is
signed with the SDK's development key and only loads in the SDK's developer
build of ATAK (see "Building it yourself").

## What it searches

Every word you type has to match something about the item (so `fire 2` finds
"Fire Line 2" but not "Water 2"). Words can match:

| Field | Examples |
|---|---|
| Name / callsign (weighted highest) | `bravo`, `lz hawk`, `truck12` |
| Remarks | `water`, `gate code` |
| Icon / type | `hostile`, `friendly`, `spot`, `route`, `casevac`, or an icon file name like `fire` |
| Group (Overlay Manager path) | `medic`, `cot`, the name of an imported data package |

Ranking: exact name > name starts with > a word starts with > contains >
one-typo match (words of 4+ letters; swapped letters count as one typo).
Ties are broken by distance from you, or from the map center if you have no
position.

Skipped: hidden items, items with no name, and helper items ATAK flags as
internal (shape handles, range-ring parts and so on).

### Imported files (KML/KMZ, shapefiles, GPX, GeoJSON)

Everything under Overlay Manager → File Overlays is searched too. For each
feature (a KMZ placemark, a shapefile row, ...):

| Field | What's in it |
|---|---|
| Name | the placemark/feature name |
| Description (remarks weight) | the KML description with HTML removed, plus ExtendedData / attribute values |
| Type | the file type (KML, SHP, ...), point/line/area, icon file name |
| Group | the file name and folder path inside it, e.g. `Incident.kmz › Division A` |

So `hydrant` finds a placemark whose description mentions a hydrant, and
`division a` finds everything in that folder. Results show the file and folder
under the name, plus the start of the description. Tapping one behaves like
tapping it in the Overlay Manager.

Limits:
- Only visible files are searched (same rule as hidden markers).
- The first search after importing, removing or toggling a file shows
  "Reading imported files…" for a moment while the index updates.
- Up to 100,000 features are indexed; descriptions are kept up to 2,000
  characters each and 4 million characters in total. Past that, features are
  still found by name.
- Typo tolerance applies to names only, not descriptions.

## Coordinate formats

| Format | Examples |
|---|---|
| MGRS | `17SNU1427798239` · `17S NU 14277 98239` · `4QFJ12345678` (2–10 digits) |
| UTM | `17S 514278 3898239` · `17S 514278mE 3898239mN` |
| Decimal degrees | `35.2271, -80.8431` · `35.2271N 80.8431W` · `N35.2271 W80.8431` · `lat: 35.2271, lon: -80.8431` |
| Degrees, minutes | `N35 13.626 W080 50.586` · `35°13.626'N 80°50.586'W` |
| Degrees, minutes, seconds | `35°13'37.6"N 80°50'35.2"W` · `35 13 37.6 N 80 50 35.2 W` |
| Map links | `https://www.google.com/maps/@35.2271,-80.8431,15z` · `maps.apple.com/?ll=…` · `geo:35.2271,-80.8431` |

Notes:
- Plain numbers are read latitude first. If the first number can't be a
  latitude but the second can (GeoJSON order, e.g. `151.2153, -33.8568`)
  they're swapped automatically.
- UTM uses the latitude band letter, as ATAK does, so `17S` means band S
  (northern hemisphere), not "south".
- MGRS and UTM are decoded with ATAK's own classes, so results match what ATAK
  shows elsewhere.
- The "Go to" row shows the point in your ATAK coordinate preference. If
  you're zoomed far out (coarser than ~3 m/pixel) the map zooms to about a
  kilometer across; otherwise it only pans and keeps your zoom.
- **Drop pin** (or long-press the row) leaves a spot marker whose remarks
  record the text you searched for.

---

# Building it yourself

You need JDK 17, Android Studio (or just the Android SDK) and the ATAK-CIV SDK
for your target version (5.6.0 SDK from tak.gov; 5.5.x SDKs are also on the
Releases page of github.com/TAK-Product-Center/atak-civ).

1. **Put this project inside the SDK**, two folders down, e.g.
   `ATAK-CIV-<version>-SDK/plugins/QuickSearch/`. The build finds `main.jar`,
   `android_keystore` and `atak-gradle-takdev.jar` by looking two folders up.
   (Elsewhere, set `sdk.path` and `takdev.plugin` in `local.properties`.)
2. **Open it in Android Studio.** It creates `local.properties` with your
   Android SDK path. Under Build → Select Build Variant, choose **civDebug** or
   **civRelease**.
3. **Check `ext.ATAK_VERSION`** in `app/build.gradle` (`'5.6.0'`). For another
   SDK, use the value from that SDK's `samples/plugintemplate/app/build.gradle`.
4. **Build** (Build → Build APK, or `./gradlew assembleCivRelease`). Before a
   new release, raise `takStaticVersion` in `gradle.properties` (1.0 = 10000,
   1.1 = 10100), or Android won't install it as an update.

APKs land in `app/build/outputs/apk/civ/`. Your own builds are signed with the
SDK's development key, so to test one, install the SDK's `atak.apk` (the
developer build, which shows "DEVELOPER BUILD" in red) on a spare device. It
replaces regular ATAK and its data, since both use the same package name.

## Project layout

```
app/src/main/java/com/atakmap/android/quicksearch/plugin/
├── QuickSearchPlugin.java    entry point: toolbar button, opens/closes the pane
├── SearchPane.java           text box, debounce, merging results, go-to and drop-pin
├── ResultAdapter.java        result rows (icon, name, details, distance)
├── SearchHit.java            one result (map item or imported feature) + ranking order
├── MapItemSearcher.java      walks map groups for markers, shapes, routes, contacts
├── FeatureIndex.java         background index of imported-file features (KML/KMZ, SHP, ...)
└── core/                     no Android code, unit-tested on a plain JVM
    ├── CoordinateParser.java MGRS / UTM / DD / DM / DMS / map-link parsing
    ├── SearchScorer.java     matching and ranking
    ├── FeatureText.java      KMZ description / attribute text cleanup
    └── GeoMath.java          distance and offset helpers
app/src/main/res/layout/      search_pane.xml, search_result_row.xml
app/src/main/assets/plugin.xml registers QuickSearchPlugin with ATAK
app/src/test/...              JUnit tests (36)
docs/user_manual/             user manual source (Typst), built by TAK.gov's pipeline
```

## Changelog

**1.0**
- Targets ATAK-CIV 5.6.0 (5.6.0 plugin template).
- Search map items and imported KML/KMZ, shapefile, GPX and GeoJSON features
  by name, description, icon/type and group.
- Paste-to-go for MGRS, UTM, DD, DM, DMS and map links, with optional pin drop.

## Ideas for a next version

- "Show all results" on the map.
- Filter chips (friendly / hostile / my markers / shapes).
- Recent searches and pinned favorites.
- Address search through ATAK's configured geocoder.

## License

Derived from the ATAK-CIV plugin template, which is distributed under the
GNU GPL v3 with the rest of ATAK-CIV. This project is offered under the same
license.
