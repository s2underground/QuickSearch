package com.atakmap.android.quicksearch.plugin;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.quicksearch.plugin.core.CoordinateParser;
import com.atakmap.android.quicksearch.plugin.core.GeoMath;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.UnitPreferences;
import com.atakmap.coremap.conversions.Span;
import com.atakmap.coremap.conversions.SpanUtilities;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rows for the results list: an optional "Go to" row, then map items. */
final class ResultAdapter extends BaseAdapter {

    static final class Row {
        final CoordinateParser.Result coordinate; // set for the "Go to" row
        final double coordinateDistance;
        final SearchHit hit; // set for item rows

        private Row(CoordinateParser.Result c, double d,
                SearchHit h) {
            coordinate = c;
            coordinateDistance = d;
            hit = h;
        }

        static Row forCoordinate(CoordinateParser.Result c, GeoPoint origin) {
            double d = origin == null ? Double.NaN
                    : GeoMath.distanceMeters(origin.getLatitude(),
                            origin.getLongitude(), c.latitude, c.longitude);
            return new Row(c, d, null);
        }

        static Row forHit(SearchHit h) {
            return new Row(null, Double.NaN, h);
        }

        boolean isCoordinate() {
            return coordinate != null;
        }
    }

    private final Context pluginContext;
    private final MapView mapView;
    private final Runnable onDropPin;
    private final UnitPreferences units;
    private final List<Row> rows = new ArrayList<>();

    ResultAdapter(Context pluginContext, MapView mapView, Runnable onDropPin) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.onDropPin = onDropPin;
        this.units = new UnitPreferences(mapView.getContext());
    }

    void setRows(List<Row> newRows) {
        rows.clear();
        rows.addAll(newRows);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public Row getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).isCoordinate() ? 0 : 1;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null)
            v = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.search_result_row, parent, false);

        ImageView icon = v.findViewById(R.id.row_icon);
        TextView title = v.findViewById(R.id.row_title);
        TextView detail = v.findViewById(R.id.row_detail);
        TextView distance = v.findViewById(R.id.row_distance);
        Button pin = v.findViewById(R.id.row_pin);

        Row row = rows.get(position);
        if (row.isCoordinate()) {
            CoordinateParser.Result c = row.coordinate;
            GeoPoint p = new GeoPoint(c.latitude, c.longitude);
            icon.clearColorFilter();
            icon.setImageResource(android.R.drawable.ic_menu_mylocation);
            title.setText(pluginContext.getString(R.string.row_go_to,
                    units.formatPoint(p, false)));
            detail.setText(pluginContext.getString(R.string.row_read_as,
                    formatName(c.format),
                    String.format(Locale.US, "%.5f, %.5f", c.latitude,
                            c.longitude)));
            distance.setText(formatDistance(row.coordinateDistance));
            pin.setVisibility(View.VISIBLE);
            pin.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View b) {
                    onDropPin.run();
                }
            });
        } else {
            SearchHit h = row.hit;
            icon.setImageDrawable(null);
            icon.clearColorFilter();
            try {
                h.bindIcon(mapView.getContext(), icon);
            } catch (RuntimeException ignored) {
                // an item with an odd icon shouldn't break the list
            }
            title.setText(h.name);
            detail.setText(h.detail);
            detail.setVisibility(h.detail.isEmpty() ? View.GONE
                    : View.VISIBLE);
            distance.setText(formatDistance(h.distance));
            pin.setVisibility(View.GONE);
            pin.setOnClickListener(null);
        }
        if (row.isCoordinate())
            detail.setVisibility(View.VISIBLE);
        return v;
    }

    private String formatDistance(double meters) {
        if (Double.isNaN(meters))
            return "";
        return SpanUtilities.formatType(units.getRangeSystem(), meters,
                Span.METER);
    }

    private String formatName(CoordinateParser.Format f) {
        switch (f) {
            case MGRS:
                return "MGRS";
            case UTM:
                return "UTM";
            case DM:
                return pluginContext.getString(R.string.fmt_dm);
            case DMS:
                return pluginContext.getString(R.string.fmt_dms);
            case LINK:
                return pluginContext.getString(R.string.fmt_link);
            case DD:
            default:
                return pluginContext.getString(R.string.fmt_dd);
        }
    }
}
