# QuickSearch — ATAK plugin

A search bar for ATAK. One box does two jobs:

1. **Find things on a cluttered map.** Type part of a name and matching
   markers, shapes, routes and contacts show up instantly, best match first,
   with their icon and distance, along with matching features from imported
   files (KML/KMZ, shapefiles, GPX, GeoJSON), searched by name *and*
   description. Tap one to fly to it; long-press to fly to it and open its
   radial menu.
2. **Jump to a coordinate.** Paste MGRS, UTM, lat/lon in any common style, or
   a Google/Apple/OSM map link. A "Go to" row appears at the top; tap it to
   move the map there, or tap **Drop pin** to also leave a spot marker.

Built on the ATAK-CIV 5.5.1.8 SDK's plugin template (`IPlugin` API). It
declares plugin API **5.5.0**, the same as the SDK's own template; ATAK 5.5.x
and newer accept plugins built for an older API, so it isn't limited to one
exact ATAK build.

---

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
internal (`addToObjList=false` on the item: shape handles, range-ring parts and
so on).

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

How it works and its limits:
- ATAK keeps imported features in a database, not as map items, and its query
  API only filters by name. QuickSearch reads the visible features once in the
  background to build its own index, and rebuilds it automatically when files
  are imported, removed, or toggled on and off. The first search after a change
  shows "Reading imported files…" for a moment.
- Only visible files are searched (same rule as hidden markers).
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
- MGRS and UTM are decoded with ATAK's own `MGRSPoint`/`UTMPoint` classes, so
  results match what ATAK shows elsewhere.
- The "Go to" row shows the point in your ATAK coordinate preference. If
  you're zoomed far out (coarser than ~3 m/pixel) the map zooms to about a
  kilometer across; otherwise it only pans and keeps your zoom.

---

## Installing the prebuilt APK (test phone)

`ATAK-Plugin-QuickSearch-0.4-5.5.0-civ-debug.apk` is signed with the SDK's
development key, so it loads only in the **developer build of ATAK** that
comes with the SDK (`atak.apk` inside `ATAK-CIV-5.5.1.8-SDK.zip`), not in the
Play Store or TAK.gov ATAK. Use a spare phone: the developer build has the same
package name as regular ATAK, so you have to remove regular ATAK first, which
deletes its data and settings on that phone.

1. Download `ATAK-CIV-5.5.1.8-SDK.zip` from the Releases page of
   github.com/TAK-Product-Center/atak-civ and unzip it. You only need
   `atak.apk` from it.
2. Copy `atak.apk` and the QuickSearch APK to the phone (USB, cloud drive,
   email to yourself).
3. Uninstall regular ATAK if it's installed.
4. Open `atak.apk` on the phone to install it. Android will ask you to allow
   installs from the app you opened it with (Files, Drive, etc.); allow it.
5. Start ATAK once and finish its first-run setup. You should see
   "DEVELOPER BUILD" in red at the bottom of the map.
6. Open the QuickSearch APK to install it.
7. Go back to ATAK. It asks "Load plugin: QuickSearch?"; tap OK. If it
   doesn't ask, open the Plugins manager from ATAK's menu and load it there.
8. The QuickSearch button is with the other tools in ATAK's menu. You can add
   it to the main toolbar through ATAK's toolbar customization. Tapping the
   button again closes the pane.

With a computer and USB debugging, steps 4 and 6 are just
`adb install atak.apk` and `adb install ATAK-Plugin-QuickSearch-…apk`.

---

## Building it yourself

You need JDK 17, Android Studio (or just the Android SDK) and the ATAK-CIV
SDK, which is the `ATAK-CIV-<version>-SDK.zip` file on the Releases page of
github.com/TAK-Product-Center/atak-civ.

### 1. Put this project inside the SDK

```
ATAK-CIV-5.5.1.8-SDK/
├── main.jar
├── android_keystore
├── atak-gradle-takdev.jar
├── atak.apk
├── samples/
└── plugins/
    └── QuickSearch/        ← this folder
```

The build finds the SDK by looking two folders up, so this layout needs no
extra configuration. (If you keep the project elsewhere, set `sdk.path` and
`takdev.plugin` in `local.properties` to point at the SDK folder and the
`atak-gradle-takdev.jar` inside it.)

### 2. Open it in Android Studio

Open the `QuickSearch` folder. Android Studio creates `local.properties` with
the path to your Android SDK. Under Build → Select Build Variant, choose
**civDebug** (otherwise it may default to another flavor).

### 3. Check the ATAK API version

`app/build.gradle` has:

```groovy
ext.ATAK_VERSION = "5.5.0"
```

That's what the 5.5.1.8 SDK's own template uses. If you switch to a newer
SDK, use the value from that SDK's `samples/plugintemplate/app/build.gradle`.

### 4. Build

Build → Build APK, or from a terminal:

```
./gradlew assembleCivDebug
```

The APK lands in `app/build/outputs/apk/civ/debug/`. Install it as in the
section above.

### 5. Run the unit tests (optional)

```
./gradlew testCivDebugUnitTest
```

These cover the coordinate parser and the ranking logic and run on your
computer, no device needed.

---

## Getting it onto everyone's phones

Users running ATAK from the Play Store or TAK.gov can only load plugins
signed by the TAK Product Center. To distribute this beyond test devices,
submit the source through TAK.gov's third-party plugin signing process (needs
a TAK.gov account). The pipeline builds and signs it for each ATAK version
you request. It also builds the user manual from
`docs/user_manual/usermanual.typ`, which is already written for this plugin.

---

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
app/src/test/...              JUnit tests
docs/user_manual/             user manual source (Typst) for the TAK.gov build
```

ATAK APIs used (all public plugin SDK): `MapView.getRootGroup()` to walk
items, `MapTouchController.goTo()` to fly to an item (same call the Overlay
Manager uses), `ATAKUtilities.scaleToFit()` and
`CameraController.Programmatic.panTo()` for coordinate jumps,
`PlacePointTool.MarkerCreator` for the pin, `UnitPreferences` and
`SpanUtilities` so coordinates and distances follow the user's settings, and
for imported files the File Overlays entries' `FeatureDataStore2` (read with
`queryFeatures`, watched with a content-changed listener) plus
`FeatureHierarchyListItem.goTo()` to fly to a feature.

## Ideas for a next version

- Filter chips (friendly / hostile / my markers / shapes).
- Recent searches and pinned favorites.
- Address search through ATAK's configured geocoder.
- A setting to choose the pin's icon and color.

## License

Derived from the ATAK-CIV plugin template, which is distributed under the
GNU GPL v3 with the rest of ATAK-CIV. Keep this project under a compatible
license if you publish it.
