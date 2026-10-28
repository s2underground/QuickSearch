package com.atakmap.android.quicksearch.plugin;

import android.content.Context;
import android.widget.BaseAdapter;
import android.widget.ImageView;

import com.atakmap.android.features.FeatureDataStoreHierarchyListItem;
import com.atakmap.android.features.FeatureDataStoreMapOverlay;
import com.atakmap.android.features.FeatureHierarchyListItem;
import com.atakmap.android.hierarchy.HierarchyListFilter;
import com.atakmap.android.hierarchy.HierarchyListItem;
import com.atakmap.android.hierarchy.action.Actions;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.overlay.MapOverlay;
import com.atakmap.android.overlay.MapOverlayParent;
import com.atakmap.android.quicksearch.plugin.core.FeatureText;
import com.atakmap.android.quicksearch.plugin.core.GeoMath;
import com.atakmap.android.quicksearch.plugin.core.SearchScorer;
import com.atakmap.android.quicksearch.plugin.core.SearchScorer.Field;
import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureCursor;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.FeatureDefinition2;
import com.atakmap.map.layer.feature.FeatureSet;
import com.atakmap.map.layer.feature.FeatureSetCursor;
import com.atakmap.map.layer.feature.geometry.Envelope;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.GeometryCollection;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.geometry.Polygon;
import com.atakmap.map.layer.feature.style.Style;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Searchable index of features in imported files: KML/KMZ, shapefiles, GPX,
 * GeoJSON and anything else ATAK lists under File Overlays.
 *
 * ATAK keeps those features in a feature database, not as map items, and
 * its query API only filters by feature name. To also search descriptions
 * and data fields, this reads every visible feature once, keeps a compact
 * normalized copy of its text, and rebuilds when the database reports a
 * change (import, delete, visibility toggle).
 *
 * Threading: {@link #refreshSources(MapView)} runs on the UI thread; building
 * and searching run on the caller's background thread.
 */
final class FeatureIndex implements
        FeatureDataStore2.OnDataStoreContentChangedListener {

    private static final String TAG = "QuickSearch.FeatureIndex";

    /** Most features indexed across all files. */
    static final int MAX_FEATURES = 100000;
    /** Total description characters kept; beyond this, names only. */
    static final long MAX_TEXT_CHARS = 4000000L;
    /** Description characters shown under a result's name. */
    private static final int SNIPPET_CHARS = 140;

    /** One imported database, as found through the File Overlays list. */
    private static final class Source {
        final FeatureDataStore2 db;
        // feature set type ("KML", "SHP", ...) -> overlay title and icon
        final Map<String, String> typeTitles = new HashMap<>();
        final Map<String, String> typeIcons = new HashMap<>();

        Source(FeatureDataStore2 db) {
            this.db = db;
        }
    }

    private static final class Entry {
        final FeatureDataStore2 db;
        final long fid;
        final String name;
        final String detail;
        final String iconUri;
        final int iconColor;
        final boolean isPoint;
        final double minX, minY, maxX, maxY;
        final SearchScorer.Doc doc;

        Entry(FeatureDataStore2 db, long fid, String name, String detail,
                String iconUri, int iconColor, boolean isPoint, Envelope env,
                SearchScorer.Doc doc) {
            this.db = db;
            this.fid = fid;
            this.name = name;
            this.detail = detail;
            this.iconUri = iconUri;
            this.iconColor = iconColor;
            this.isPoint = isPoint;
            this.minX = env.minX;
            this.minY = env.minY;
            this.maxX = env.maxX;
            this.maxY = env.maxY;
            this.doc = doc;
        }
    }

    /** A feature from an imported file. */
    static final class FeatureHit extends SearchHit {
        private final Entry e;

        FeatureHit(Entry e, int score, double distance) {
            super(e.name, e.detail, score, distance);
            this.e = e;
        }

        @Override
        void goTo(boolean openMenu) {
            // the same list item the Overlay Manager uses, so tapping a
            // result behaves exactly like tapping it there
            new FeatureHierarchyListItem(e.db, e.fid, e.name, e.iconUri,
                    e.iconColor, e.isPoint, e.minX, e.minY, e.maxX, e.maxY)
                    .goTo(openMenu);
        }

        @Override
        void bindIcon(Context context, ImageView view) {
            if (e.iconUri != null)
                ATAKUtilities.setIcon(context, view, e.iconUri, e.iconColor);
        }
    }

    // UI thread only
    private final Map<MapOverlay, Source> overlaySources = new IdentityHashMap<>();

    // guarded by this
    private final Map<FeatureDataStore2, Source> sources = new IdentityHashMap<>();
    private List<Entry> entries = Collections.emptyList();
    private volatile boolean dirty = true;
    private int lastIndexedCount;

    // ---- UI thread ------------------------------------------------------

    /**
     * Find the feature databases behind the File Overlays list. Cheap after
     * the first call; new overlays (a new file type imported) are picked up.
     */
    void refreshSources(MapView mapView) {
        List<FeatureDataStoreMapOverlay> found = new ArrayList<>();
        collect(mapView.getMapOverlayManager().getOverlays(), found, 0);

        boolean changed = false;
        Map<FeatureDataStore2, Source> next = new IdentityHashMap<>();
        for (FeatureDataStoreMapOverlay overlay : found) {
            Source src = overlaySources.get(overlay);
            if (src == null) {
                src = describe(overlay);
                if (src == null)
                    continue;
                overlaySources.put(overlay, src);
                changed = true;
            }
            // fresh objects each time, so the background thread never sees
            // a map that is still being filled in
            Source merged = next.get(src.db);
            if (merged == null) {
                merged = new Source(src.db);
                next.put(src.db, merged);
            }
            merged.typeTitles.putAll(src.typeTitles);
            merged.typeIcons.putAll(src.typeIcons);
        }

        synchronized (this) {
            for (FeatureDataStore2 db : next.keySet()) {
                if (!sources.containsKey(db)) {
                    db.addOnDataStoreContentChangedListener(this);
                    changed = true;
                }
            }
            for (FeatureDataStore2 db : sources.keySet()) {
                if (!next.containsKey(db)) {
                    db.removeOnDataStoreContentChangedListener(this);
                    changed = true;
                }
            }
            sources.clear();
            sources.putAll(next);
        }
        if (changed)
            dirty = true;
    }

    private static void collect(Collection<MapOverlay> overlays,
            List<FeatureDataStoreMapOverlay> out, int depth) {
        if (overlays == null || depth > 4)
            return;
        for (MapOverlay o : overlays) {
            if (o instanceof FeatureDataStoreMapOverlay)
                out.add((FeatureDataStoreMapOverlay) o);
            else if (o instanceof MapOverlayParent)
                collect(((MapOverlayParent) o).getOverlays(), out, depth + 1);
        }
    }

    /** Learn the database, title and icon behind one File Overlays entry. */
    private static Source describe(FeatureDataStoreMapOverlay overlay) {
        try {
            // no adapter: the list model then registers no change listener
            HierarchyListItem li = overlay.getListModel((BaseAdapter) null,
                    Actions.ACTION_GOTO, (HierarchyListFilter) null);
            if (!(li instanceof FeatureDataStoreHierarchyListItem))
                return null;
            FeatureDataStoreHierarchyListItem fli = (FeatureDataStoreHierarchyListItem) li;
            Source src = new Source(fli.getFeatureDatabase());
            FeatureDataStore2.FeatureQueryParameters p = fli
                    .getFeatureQueryParams();
            if (p != null && p.featureSetFilter != null
                    && p.featureSetFilter.types != null) {
                for (String type : p.featureSetFilter.types) {
                    src.typeTitles.put(type, overlay.getName());
                    src.typeIcons.put(type, fli.getIconUri());
                }
            }
            return src;
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read overlay " + overlay.getName(), e);
            return null;
        }
    }

    // ---- background thread ----------------------------------------------

    /** Features in the current index (0 until the first build). */
    synchronized int getIndexedCount() {
        return lastIndexedCount;
    }

    boolean needsRebuild() {
        return dirty;
    }

    /**
     * @param origin point distances are measured from; may be null
     */
    List<SearchHit> search(SearchScorer.Query q, GeoPoint origin) {
        List<Entry> snapshot = ensureBuilt();
        List<SearchHit> hits = new ArrayList<>();
        if (q.isEmpty())
            return hits;
        for (Entry e : snapshot) {
            int s = SearchScorer.score(q, e.doc);
            if (s <= 0)
                continue;
            double dist = Double.NaN;
            if (origin != null)
                dist = GeoMath.distanceMeters(origin.getLatitude(),
                        origin.getLongitude(), (e.minY + e.maxY) / 2,
                        (e.minX + e.maxX) / 2);
            hits.add(new FeatureHit(e, s, dist));
        }
        return hits;
    }

    /**
     * Only the single search thread calls this. The build itself runs
     * without holding the lock, so the UI thread is never blocked by it.
     */
    private List<Entry> ensureBuilt() {
        List<Source> srcs;
        synchronized (this) {
            if (!dirty)
                return entries;
            dirty = false; // changes during the build set it again
            srcs = new ArrayList<>(sources.values());
        }
        List<Entry> built = build(srcs);
        synchronized (this) {
            entries = built;
            lastIndexedCount = built.size();
        }
        return built;
    }

    private static List<Entry> build(List<Source> srcs) {
        List<Entry> out = new ArrayList<>();
        long textBudget = MAX_TEXT_CHARS;
        long start = System.currentTimeMillis();

        for (Source src : srcs) {
            Map<Long, FeatureSet> sets = featureSets(src.db);

            FeatureDataStore2.FeatureQueryParameters params = new FeatureDataStore2.FeatureQueryParameters();
            params.visibleOnly = true;
            params.limit = MAX_FEATURES - out.size();
            if (params.limit <= 0)
                break;

            FeatureCursor cursor = null;
            try {
                cursor = src.db.queryFeatures(params);
                while (cursor.moveToNext()) {
                    Feature f = cursor.get();
                    if (f == null)
                        continue;
                    Map<String, String> attrs = attributes(f.getAttributes());
                    String name = FeatureText.displayName(f.getName(), attrs);
                    Geometry geom = f.getGeometry();
                    if (name == null || geom == null)
                        continue;
                    Envelope env = geom.getEnvelope();
                    if (env == null || Double.isNaN(env.minX))
                        continue;

                    String text = textBudget > 0
                            ? FeatureText.searchableText(attrs)
                            : "";
                    textBudget -= text.length();

                    FeatureSet set = sets.get(f.getFeatureSetId());
                    String path = set == null || set.getName() == null ? ""
                            : set.getName().replace("/", " › ");
                    String type = set == null ? null : set.getType();
                    String sourceTitle = type == null ? null
                            : src.typeTitles.get(type);

                    Style style = f.getStyle();
                    String iconUri = FeatureHierarchyListItem
                            .iconUriFromStyle(style);
                    int iconColor = FeatureHierarchyListItem
                            .colorFromStyle(style);
                    if (iconUri == null && type != null)
                        iconUri = src.typeIcons.get(type);

                    String[] fields = new String[Field.values().length];
                    fields[Field.NAME.ordinal()] = name;
                    fields[Field.REMARKS.ordinal()] = text;
                    fields[Field.TYPE.ordinal()] = join(sourceTitle, type,
                            geometryWords(geom), iconWords(iconUri));
                    fields[Field.GROUP.ordinal()] = path;

                    out.add(new Entry(src.db, f.getId(), name,
                            detail(path, text), iconUri, iconColor,
                            geom instanceof Point, env,
                            SearchScorer.doc(fields)));
                    if (out.size() >= MAX_FEATURES)
                        break;
                }
            } catch (Exception e) {
                Log.w(TAG, "error indexing " + src.db.getUri(), e);
            } finally {
                if (cursor != null)
                    cursor.close();
            }
        }
        Log.d(TAG, "indexed " + out.size() + " features in "
                + (System.currentTimeMillis() - start) + " ms");
        return out;
    }

    private static Map<Long, FeatureSet> featureSets(FeatureDataStore2 db) {
        Map<Long, FeatureSet> sets = new HashMap<>();
        FeatureSetCursor c = null;
        try {
            c = db.queryFeatureSets(
                    new FeatureDataStore2.FeatureSetQueryParameters());
            while (c.moveToNext()) {
                FeatureSet s = c.get();
                if (s != null)
                    sets.put(s.getId(), s);
            }
        } catch (Exception e) {
            Log.w(TAG, "error reading feature sets", e);
        } finally {
            if (c != null)
                c.close();
        }
        return sets;
    }

    /** Text-like attribute values, in their original order. */
    private static Map<String, String> attributes(AttributeSet a) {
        Map<String, String> out = new LinkedHashMap<>();
        if (a == null)
            return out;
        Set<String> names = a.getAttributeNames();
        if (names == null)
            return out;
        for (String key : names) {
            try {
                Class<?> t = a.getAttributeType(key);
                String v = null;
                if (t == String.class)
                    v = a.getStringAttribute(key);
                else if (t == Integer.TYPE || t == Integer.class)
                    v = String.valueOf(a.getIntAttribute(key));
                else if (t == Long.TYPE || t == Long.class)
                    v = String.valueOf(a.getLongAttribute(key));
                else if (t == Double.TYPE || t == Double.class)
                    v = trimDouble(a.getDoubleAttribute(key));
                else if (t == String[].class) {
                    String[] arr = a.getStringArrayAttribute(key);
                    if (arr != null)
                        v = join(arr);
                }
                if (v != null)
                    out.put(key, v);
            } catch (RuntimeException ignored) {
                // skip attributes that fail to read
            }
        }
        return out;
    }

    private static String trimDouble(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15)
            return String.valueOf((long) d);
        return String.valueOf(d);
    }

    private static String geometryWords(Geometry g) {
        if (g instanceof Point)
            return "point";
        if (g instanceof LineString)
            return "line";
        if (g instanceof Polygon)
            return "area polygon";
        if (g instanceof GeometryCollection)
            return "collection";
        return null;
    }

    private static String iconWords(String iconUri) {
        if (iconUri == null)
            return null;
        String file = iconUri.substring(iconUri.lastIndexOf('/') + 1);
        int q = file.indexOf('?');
        if (q >= 0)
            file = file.substring(0, q);
        int dot = file.lastIndexOf('.');
        if (dot > 0)
            file = file.substring(0, dot);
        return file.replace('_', ' ').replace('-', ' ');
    }

    private static String detail(String path, String text) {
        String snippet = text;
        if (snippet.length() > SNIPPET_CHARS)
            snippet = snippet.substring(0, SNIPPET_CHARS) + "…";
        if (path.isEmpty())
            return snippet;
        return snippet.isEmpty() ? path : path + " · " + snippet;
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty())
                continue;
            if (sb.length() > 0)
                sb.append(' ');
            sb.append(p);
        }
        return sb.toString();
    }

    void dispose() {
        synchronized (this) {
            for (FeatureDataStore2 db : sources.keySet())
                db.removeOnDataStoreContentChangedListener(this);
            sources.clear();
            entries = Collections.emptyList();
        }
        overlaySources.clear();
    }

    // ---- change notifications (any thread) ------------------------------

    @Override
    public void onDataStoreContentChanged(FeatureDataStore2 dataStore) {
        dirty = true;
    }

    @Override
    public void onFeatureInserted(FeatureDataStore2 dataStore, long fid,
            FeatureDefinition2 def, long version) {
        dirty = true;
    }

    @Override
    public void onFeatureUpdated(FeatureDataStore2 dataStore, long fid,
            int modificationMask, String name, Geometry geom, Style style,
            AttributeSet attribs, int attribsUpdateType) {
        dirty = true;
    }

    @Override
    public void onFeatureDeleted(FeatureDataStore2 dataStore, long fid) {
        dirty = true;
    }

    @Override
    public void onFeatureVisibilityChanged(FeatureDataStore2 dataStore,
            long fid, boolean visible) {
        dirty = true;
    }
}
