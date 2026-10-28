package com.atakmap.android.quicksearch.plugin;

import android.content.Context;
import android.widget.ImageView;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapTouchController;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.quicksearch.plugin.core.GeoMath;
import com.atakmap.android.quicksearch.plugin.core.SearchScorer;
import com.atakmap.android.quicksearch.plugin.core.SearchScorer.Field;
import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks every map group and scores each visible item against the query.
 * Runs on the UI thread; map items are few enough that this is instant.
 *
 * Skips helper items ATAK flags addToObjList=false (range-ring parts, shape
 * handles, the radial focus marker and so on), items with no name, and items
 * that are currently hidden on the map.
 */
final class MapItemSearcher {

    /** A marker, shape, route or contact on the map. */
    static final class MapItemHit extends SearchHit {
        final MapItem item;

        MapItemHit(MapItem item, String name, String detail, int score,
                double distance) {
            super(name, detail, score, distance);
            this.item = item;
        }

        @Override
        void goTo(boolean openMenu) {
            MapTouchController.goTo(item, openMenu);
        }

        @Override
        void bindIcon(Context context, ImageView view) {
            ATAKUtilities.setIcon(view, item);
        }
    }

    private final MapView mapView;

    /** Named, visible items looked at by the last search. */
    private int searchedCount;

    MapItemSearcher(MapView mapView) {
        this.mapView = mapView;
    }

    int getSearchedCount() {
        return searchedCount;
    }

    /**
     * @param origin point distances are measured from; may be null
     */
    List<SearchHit> search(SearchScorer.Query q, GeoPoint origin) {
        List<SearchHit> hits = new ArrayList<>();
        searchedCount = 0;
        if (q.isEmpty() || mapView == null)
            return hits;

        MapGroup root = mapView.getRootGroup();
        if (root == null)
            return hits;
        walk(root, "", q, origin, hits);
        return hits;
    }

    private void walk(MapGroup group, String path, SearchScorer.Query q,
            GeoPoint origin, List<SearchHit> out) {
        // Note: addToObjList=false on a *group* does not mean "internal".
        // ATAK sets it on "User Objects" and "Cursor on Target", which hold
        // nearly every real marker, so the Overlay Manager can list their
        // contents through its own filters instead. Only the per-item flag
        // marks helper items, and that is checked in searchable().
        String name = group.getFriendlyName();
        String here = path;
        boolean isRoot = group == mapView.getRootGroup();
        if (!isRoot && name != null && !name.isEmpty())
            here = path.isEmpty() ? name : path + " › " + name;

        for (MapItem item : group.getItems()) {
            if (!searchable(item))
                continue;
            searchedCount++;
            SearchHit h = score(item, here, q, origin);
            if (h != null)
                out.add(h);
        }
        for (MapGroup child : group.getChildGroups())
            walk(child, here, q, origin, out);
    }

    private static boolean searchable(MapItem item) {
        return item != null && item.getVisible()
                && item.getMetaBoolean("addToObjList", true)
                && nameOf(item) != null;
    }

    private static String nameOf(MapItem item) {
        String name = item.getTitle();
        if (name == null || name.trim().isEmpty())
            name = item.getMetaString("callsign", null);
        if (name == null || name.trim().isEmpty())
            return null;
        return name;
    }

    private static SearchHit score(MapItem item, String groupPath,
            SearchScorer.Query q, GeoPoint origin) {
        String name = nameOf(item);
        String remarks = item.getMetaString("remarks", null);
        String type = describeType(item);

        String[] fields = new String[Field.values().length];
        fields[Field.NAME.ordinal()] = name;
        fields[Field.REMARKS.ordinal()] = remarks;
        fields[Field.TYPE.ordinal()] = type;
        fields[Field.GROUP.ordinal()] = groupPath;

        int s = SearchScorer.score(q, fields);
        if (s <= 0)
            return null;

        GeoPoint loc = locationOf(item);
        double dist = Double.NaN;
        if (loc != null && origin != null)
            dist = GeoMath.distanceMeters(origin.getLatitude(),
                    origin.getLongitude(), loc.getLatitude(),
                    loc.getLongitude());

        String detail = groupPath;
        if (remarks != null && !remarks.trim().isEmpty())
            detail = detail.isEmpty() ? remarks.trim()
                    : detail + " · " + remarks.trim();
        return new MapItemHit(item, name.trim(), detail, s, dist);
    }

    static GeoPoint locationOf(MapItem item) {
        GeoPoint p = null;
        if (item instanceof PointMapItem) {
            p = ((PointMapItem) item).getPoint();
        } else if (item instanceof Shape) {
            GeoPointMetaData c = ((Shape) item).getCenter();
            if (c != null)
                p = c.get();
        }
        return p != null && p.isValid() ? p : null;
    }

    /**
     * Searchable words for an item's kind: the raw CoT type, the icon file
     * name (so "fire" finds every fire icon), and a few plain-English
     * words for common CoT type families.
     */
    static String describeType(MapItem item) {
        StringBuilder sb = new StringBuilder();
        String type = item.getType();
        if (type != null) {
            sb.append(type).append(' ');
            if (type.startsWith("a-f"))
                sb.append("friendly ");
            else if (type.startsWith("a-h"))
                sb.append("hostile enemy ");
            else if (type.startsWith("a-n"))
                sb.append("neutral ");
            else if (type.startsWith("a-u"))
                sb.append("unknown ");
            if (type.startsWith("b-m-p-s-m"))
                sb.append("spot ");
            else if (type.startsWith("b-m-r"))
                sb.append("route ");
            else if (type.startsWith("u-d-"))
                sb.append("drawing shape ");
            else if (type.startsWith("b-m-p-w"))
                sb.append("waypoint ");
            else if (type.startsWith("b-m-p-c"))
                sb.append("checkpoint ");
            else if (type.startsWith("b-r-f-h-c"))
                sb.append("casevac medevac ");
        }
        String icon = item.getMetaString("iconsetpath", null);
        if (icon != null && !icon.isEmpty()) {
            String file = icon.substring(icon.lastIndexOf('/') + 1);
            int dot = file.lastIndexOf('.');
            if (dot > 0)
                file = file.substring(0, dot);
            sb.append(file.replace('_', ' ').replace('-', ' '));
        }
        return sb.toString();
    }
}
