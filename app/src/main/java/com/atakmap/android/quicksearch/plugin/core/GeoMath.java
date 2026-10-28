package com.atakmap.android.quicksearch.plugin.core;

/** Small geodesy helpers with no Android dependencies. */
public final class GeoMath {

    private static final double EARTH_RADIUS_M = 6371008.8;

    private GeoMath() {
    }

    /** Great-circle distance in meters. NaN if any input is NaN. */
    public static double distanceMeters(double lat1, double lon1,
            double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2)
                        * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /**
     * Offset a point by a distance north and east, in meters. Used to build
     * a small box around a coordinate so the map can zoom to it.
     *
     * @return {lat, lon}
     */
    public static double[] offset(double lat, double lon, double northM,
            double eastM) {
        double dLat = Math.toDegrees(northM / EARTH_RADIUS_M);
        double cos = Math.cos(Math.toRadians(lat));
        double dLon = cos < 1e-9 ? 0
                : Math.toDegrees(eastM / (EARTH_RADIUS_M * cos));
        double nLat = Math.max(-90, Math.min(90, lat + dLat));
        double nLon = lon + dLon;
        if (nLon > 180)
            nLon -= 360;
        if (nLon < -180)
            nLon += 360;
        return new double[] {
                nLat, nLon
        };
    }
}
