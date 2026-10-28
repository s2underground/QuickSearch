package com.atakmap.android.quicksearch.plugin;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.quicksearch.plugin.core.CoordinateParser;
import com.atakmap.android.quicksearch.plugin.core.GeoMath;
import com.atakmap.android.quicksearch.plugin.core.SearchScorer;
import com.atakmap.android.user.PlacePointTool;
import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.CameraController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The search panel: a text box, a status line and a results list.
 *
 * Typing searches map items (markers, shapes, routes, contacts) right away
 * on the UI thread, and features in imported files (KML/KMZ, shapefiles,
 * GPX, GeoJSON) on a background thread; their results are merged when they
 * arrive. If the text is a coordinate, a "Go to" row is shown at the top.
 * Tapping a row moves the map there; long-pressing also opens its radial
 * menu.
 */
final class SearchPane {

    private static final String TAG = "QuickSearch.SearchPane";

    static final int MAX_RESULTS = 200;
    private static final long DEBOUNCE_MS = 200;
    // when zoomed out further than this (meters per pixel), a coordinate
    // jump also zooms in; otherwise it only pans and keeps the user's zoom
    private static final double ZOOM_IN_IF_COARSER_THAN = 3.0;
    // half-width of the area shown after zooming to a coordinate
    private static final double COORD_VIEW_HALF_WIDTH_M = 400;

    private final Context pluginContext;
    private final MapView mapView;
    private final MapItemSearcher searcher;
    private final FeatureIndex featureIndex = new FeatureIndex();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "QuickSearch-files");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            });

    private final View root;
    private final EditText input;
    private final ImageButton clear;
    private final TextView status;
    private final ResultAdapter adapter;

    // UI thread state for the current search
    private CoordinateParser.Result coordinate;
    private String coordinateText = "";
    private GeoPoint origin;
    private boolean originIsSelf;
    private List<SearchHit> itemHits = Collections.emptyList();
    private List<SearchHit> featureHits = Collections.emptyList();
    private boolean featuresPending;
    private volatile int searchSeq; // read by the worker thread

    private final Runnable runSearch = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };
    private final Runnable showKeyboardTask = new Runnable() {
        @Override
        public void run() {
            showKeyboard();
        }
    };

    SearchPane(Context pluginContext, MapView mapView) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.searcher = new MapItemSearcher(mapView);

        root = PluginLayoutInflater.inflate(pluginContext,
                R.layout.search_pane, null);
        input = root.findViewById(R.id.search_input);
        clear = root.findViewById(R.id.search_clear);
        status = root.findViewById(R.id.search_status);
        ListView list = root.findViewById(R.id.search_results);

        adapter = new ResultAdapter(pluginContext, mapView,
                new Runnable() {
                    @Override
                    public void run() {
                        dropPinAtCoordinate();
                    }
                });
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view,
                    int position, long id) {
                ResultAdapter.Row row = adapter.getItem(position);
                if (row.isCoordinate())
                    goToCoordinate();
                else
                    goTo(row.hit, false);
            }
        });
        list.setOnItemLongClickListener(
                new AdapterView.OnItemLongClickListener() {
                    @Override
                    public boolean onItemLongClick(AdapterView<?> parent,
                            View view, int position, long id) {
                        ResultAdapter.Row row = adapter.getItem(position);
                        if (row.isCoordinate())
                            dropPinAtCoordinate();
                        else
                            goTo(row.hit, true);
                        return true;
                    }
                });

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c,
                    int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                clear.setVisibility(s.length() > 0 ? View.VISIBLE
                        : View.INVISIBLE);
                handler.removeCallbacks(runSearch);
                handler.postDelayed(runSearch, DEBOUNCE_MS);
            }
        });

        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId,
                    KeyEvent event) {
                return onEnter(actionId, event);
            }
        });

        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setText("");
                input.requestFocus();
                showKeyboard();
            }
        });
        clear.setVisibility(View.INVISIBLE);

        refresh();
    }

    /** Enter / Search on the keyboard: go to the coordinate or top hit. */
    private boolean onEnter(int actionId, KeyEvent event) {
        boolean enter = actionId == EditorInfo.IME_ACTION_SEARCH
                || actionId == EditorInfo.IME_ACTION_GO
                || actionId == EditorInfo.IME_ACTION_DONE
                || (event != null
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN);
        if (!enter)
            return false;
        handler.removeCallbacks(runSearch);
        if (!input.getText().toString().trim().equals(coordinateText))
            refresh();
        if (coordinate != null) {
            goToCoordinate();
        } else {
            for (int i = 0; i < adapter.getCount(); i++) {
                ResultAdapter.Row row = adapter.getItem(i);
                if (!row.isCoordinate()) {
                    goTo(row.hit, false);
                    break;
                }
            }
        }
        return true;
    }

    View getView() {
        return root;
    }

    /** Called each time the pane is shown. */
    void onShown() {
        refresh(); // the map may have changed since last time
        input.requestFocus();
        input.selectAll();
        handler.postDelayed(showKeyboardTask, 150);
    }

    void onHidden() {
        handler.removeCallbacks(runSearch);
        hideKeyboard();
    }

    void dispose() {
        handler.removeCallbacksAndMessages(null);
        searchSeq++; // drop any result still in flight
        worker.shutdownNow();
        featureIndex.dispose();
    }

    // ---- searching ------------------------------------------------------

    private void refresh() {
        String text = input.getText().toString();
        coordinate = CoordinateParser.parse(text);
        coordinateText = text.trim();

        GeoPoint self = selfLocation();
        originIsSelf = self != null;
        GeoPoint o = self != null ? self : mapView.getCenterPoint().get();
        origin = o == null ? null
                : new GeoPoint(o.getLatitude(), o.getLongitude());

        final SearchScorer.Query q = SearchScorer.prepare(text);

        // map items: instant, on this thread
        itemHits = searcher.search(q, origin);

        // imported files: on the worker thread, merged in when done
        final int seq = ++searchSeq;
        featureHits = Collections.emptyList();
        featuresPending = false;
        if (!q.isEmpty()) {
            try {
                featureIndex.refreshSources(mapView);
            } catch (RuntimeException e) {
                Log.w(TAG, "could not list imported files", e);
            }
            featuresPending = true;
            final GeoPoint from = origin;
            try {
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        searchFiles(seq, q, from);
                    }
                });
            } catch (RuntimeException e) {
                featuresPending = false; // worker shut down
            }
        }
        showResults();
    }

    /** Worker thread. */
    private void searchFiles(final int seq, SearchScorer.Query q,
            GeoPoint from) {
        if (seq != searchSeq)
            return; // a newer search is already queued
        List<SearchHit> found;
        try {
            found = featureIndex.search(q, from);
        } catch (Throwable t) {
            Log.e(TAG, "imported file search failed", t);
            found = Collections.emptyList();
        }
        final List<SearchHit> result = found;
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (seq != searchSeq)
                    return; // stale
                featureHits = result;
                featuresPending = false;
                showResults();
            }
        });
    }

    private void showResults() {
        List<SearchHit> all = new ArrayList<>(itemHits.size()
                + featureHits.size());
        all.addAll(itemHits);
        all.addAll(featureHits);
        Collections.sort(all, SearchHit.BY_RANK);
        int total = all.size();
        if (all.size() > MAX_RESULTS)
            all = all.subList(0, MAX_RESULTS);

        List<ResultAdapter.Row> rows = new ArrayList<>(all.size() + 1);
        if (coordinate != null)
            rows.add(ResultAdapter.Row.forCoordinate(coordinate, origin));
        for (SearchHit h : all)
            rows.add(ResultAdapter.Row.forHit(h));
        adapter.setRows(rows);

        status.setText(statusText(total));
    }

    private String statusText(int count) {
        if (coordinateText.isEmpty())
            return pluginContext.getString(R.string.status_hint);

        String from = pluginContext.getString(originIsSelf
                ? R.string.status_from_self
                : R.string.status_from_center);
        String pending = featuresPending
                ? "  " + pluginContext.getString(featureIndex.needsRebuild()
                        ? R.string.status_indexing
                        : R.string.status_searching_files)
                : "";

        if (coordinate != null && count == 0)
            return pluginContext.getString(R.string.status_coordinate)
                    + "  " + from + pending;
        if (count == 0) {
            if (featuresPending)
                return pending.trim();
            return pluginContext.getString(R.string.status_none,
                    searcher.getSearchedCount(),
                    featureIndex.getIndexedCount());
        }
        String n = count > MAX_RESULTS
                ? pluginContext.getString(R.string.status_many, MAX_RESULTS)
                : pluginContext.getResources().getQuantityString(
                        R.plurals.status_count, count, count);
        return n + "  " + from + pending;
    }

    private GeoPoint selfLocation() {
        Marker self = mapView.getSelfMarker();
        if (self == null || self.getGroup() == null)
            return null;
        GeoPoint p = self.getPoint();
        if (p == null || !p.isValid()
                || (p.getLatitude() == 0 && p.getLongitude() == 0))
            return null;
        return p;
    }

    // ---- actions --------------------------------------------------------

    private void goTo(SearchHit hit, boolean openMenu) {
        if (hit == null)
            return;
        hideKeyboard();
        try {
            hit.goTo(openMenu);
        } catch (RuntimeException e) {
            // e.g. the item or file was removed since the search ran
            Log.w(TAG, "could not go to " + hit.name, e);
            refresh();
        }
    }

    private void goToCoordinate() {
        if (coordinate == null)
            return;
        hideKeyboard();
        double lat = coordinate.latitude, lon = coordinate.longitude;
        GeoPoint target = new GeoPoint(lat, lon);

        if (mapView.getMapResolution() > ZOOM_IN_IF_COARSER_THAN) {
            double[] sw = GeoMath.offset(lat, lon, -COORD_VIEW_HALF_WIDTH_M,
                    -COORD_VIEW_HALF_WIDTH_M);
            double[] ne = GeoMath.offset(lat, lon, COORD_VIEW_HALF_WIDTH_M,
                    COORD_VIEW_HALF_WIDTH_M);
            boolean ok = ATAKUtilities.scaleToFit(mapView, new GeoPoint[] {
                    new GeoPoint(sw[0], sw[1]), new GeoPoint(ne[0], ne[1])
            }, mapView.getWidth(), mapView.getHeight());
            if (ok)
                return;
        }
        CameraController.Programmatic.panTo(mapView.getRenderer3(), target,
                true);
    }

    /** Go to the pasted coordinate and leave a spot marker there. */
    private void dropPinAtCoordinate() {
        if (coordinate == null)
            return;
        goToCoordinate();
        GeoPoint target = new GeoPoint(coordinate.latitude,
                coordinate.longitude);
        new PlacePointTool.MarkerCreator(target)
                .setType("b-m-p-s-m")
                .setColor(Color.YELLOW)
                .setMetaString("remarks",
                        pluginContext.getString(R.string.pin_remarks,
                                coordinateText))
                .showCotDetails(false)
                .placePoint();
        mapView.post(runSearch);
    }

    // ---- keyboard -------------------------------------------------------

    private InputMethodManager imm() {
        return (InputMethodManager) mapView.getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    private void showKeyboard() {
        InputMethodManager imm = imm();
        if (imm != null && input.isShown())
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = imm();
        if (imm != null)
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }
}
