
#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "QuickSearch",
   plugin-version: "1.0",
   platform: "ATAK",
   platform-version: "5.6.0",
)


#tak-slide[
  = Overview
#toolbox.side-by-side(columns: (.75fr, 9fr))[
#image("plugin_icon.png", width: 70%)
][
 QuickSearch adds a search bar to ATAK. Use it to find markers and imported
 KML/KMZ features on a busy map by name or description, and to jump the map to
 a coordinate you paste.
]
= Finding map items
Tap the QuickSearch button on the toolbar and start typing. Results update as
you type and are ranked by how closely they match, then by distance from you
(or from the map center when you have no position).

Every word you type must match something about the item. Words can match the
item's name or callsign, its remarks, its icon or type ("hostile", "spot",
"route", or an icon file name such as "fire"), or the group it sits in. Small
typos in longer words are tolerated.

- Tap a result to pan and zoom to it.
- Long-press a result to go to it and open its radial menu.
- Press Search on the keyboard to go to the top result.

Hidden items and ATAK's internal helper items are not searched.

== Imported files
Features in imported KML/KMZ, shapefile, GPX and GeoJSON files are searched
too, by name, by description (including KMZ ExtendedData), and by the file and
folder they are in. Results show the file and folder under the name. Only files
that are visible on the map are searched. The first search after importing or
toggling a file shows "Reading imported files…" briefly while QuickSearch
updates its index.
]

#tak-slide[
= Jumping to a coordinate
Paste or type a coordinate. When the text is recognized, a "Go to" row appears
at the top of the list showing the point in your preferred coordinate format.

Recognized formats:
- MGRS: `17SNU1427798239`, `17S NU 14277 98239`
- UTM: `17S 514278 3898239`
- Decimal degrees: `35.2271, -80.8431`, `35.2271N 80.8431W`
- Degrees and minutes: `N35 13.626 W080 50.586`
- Degrees, minutes and seconds: `35°13'37.6"N 80°50'35.2"W`
- Map links: Google Maps, Apple Maps, OpenStreetMap and `geo:` links

Tap the row to move the map there. If you are zoomed far out the map zooms in
to about a kilometer across; otherwise your zoom is kept. Tap "Drop pin" (or
long-press the row) to also leave a spot marker at that point; its remarks
record the text you searched for.

Plain lat/lon numbers are read latitude first. If the first number can't be a
latitude but the second can (for example `151.2153, -33.8568`), they are
swapped.
]
