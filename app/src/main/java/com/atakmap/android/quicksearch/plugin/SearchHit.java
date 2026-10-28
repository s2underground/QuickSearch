package com.atakmap.android.quicksearch.plugin;

import android.content.Context;
import android.widget.ImageView;

import java.util.Comparator;

/**
 * One search result: either a map item (marker, shape, route, contact) or a
 * feature from an imported file (KML/KMZ, shapefile, GPX, GeoJSON, ...).
 */
abstract class SearchHit {

    final String name;
    final String detail;
    final int score;
    final double distance; // meters from the search origin, NaN if unknown

    SearchHit(String name, String detail, int score, double distance) {
        this.name = name;
        this.detail = detail == null ? "" : detail;
        this.score = score;
        this.distance = distance;
    }

    /** Pan/zoom the map to the result; optionally open its radial menu. */
    abstract void goTo(boolean openMenu);

    /** Show the result's icon. */
    abstract void bindIcon(Context context, ImageView view);

    /** Best score first, then nearest, then alphabetical. */
    static final Comparator<SearchHit> BY_RANK = new Comparator<SearchHit>() {
        @Override
        public int compare(SearchHit a, SearchHit b) {
            if (a.score != b.score)
                return a.score > b.score ? -1 : 1;
            boolean an = Double.isNaN(a.distance);
            boolean bn = Double.isNaN(b.distance);
            if (an != bn)
                return an ? 1 : -1;
            if (!an && a.distance != b.distance)
                return Double.compare(a.distance, b.distance);
            return a.name.compareToIgnoreCase(b.name);
        }
    };
}
