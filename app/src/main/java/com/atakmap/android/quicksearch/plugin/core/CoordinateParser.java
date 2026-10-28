package com.atakmap.android.quicksearch.plugin.core;

import com.atakmap.coremap.maps.coords.Ellipsoid;
import com.atakmap.coremap.maps.coords.MGRSPoint;
import com.atakmap.coremap.maps.coords.UTMPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns whatever a user pastes into the search box into a latitude/longitude,
 * or returns null if the text is not a coordinate.
 *
 * Handles:
 *   MGRS      17SNV1234567890, 17S NV 12345 67890, 4QFJ12345678
 *   UTM       17S 512345 3895000
 *   DD        35.2271, -80.8431    35.2271N 80.8431W    N35.2271 W80.8431
 *   DM        N35 13.627 W080 50.587    35°13.627'N 80°50.587'W
 *   DMS       35°13'37.6"N 80°50'35.2"W    35 13 37.6 N 80 50 35.2 W
 *   Map links Google/Apple/OSM links and geo: URIs that carry lat,lon
 *
 * No Android dependencies, so it can be unit tested on a plain JVM.
 */
public final class CoordinateParser {

    public enum Format {
        MGRS, UTM, DD, DM, DMS, LINK
    }

    public static final class Result {
        public final double latitude;
        public final double longitude;
        public final Format format;

        Result(double latitude, double longitude, Format format) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.format = format;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s %.6f, %.6f", format,
                    latitude, longitude);
        }
    }

    private CoordinateParser() {
    }

    // ---- patterns -------------------------------------------------------

    // lat,lon pair inside a URL or geo: URI (comma may be URL-encoded)
    private static final Pattern LINK_PAIR = Pattern.compile(
            "(-?\\d{1,2}(?:\\.\\d+)?)\\s*(?:,|%2C)\\s*(-?\\d{1,3}(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);
    // Google Maps "@lat,lon,zoom" form takes priority over other pairs
    private static final Pattern LINK_AT = Pattern.compile(
            "@(-?\\d{1,2}(?:\\.\\d+)?),(-?\\d{1,3}(?:\\.\\d+)?)");

    // MGRS: zone, band (no I/O), 100 km column (no I/O), row (A-V, no I/O),
    // then 0-10 digits
    private static final Pattern MGRS = Pattern.compile(
            "^(\\d{1,2})([C-HJ-NP-X])([A-HJ-NP-Z])([A-HJ-NP-V])(\\d{0,10})$");
    private static final Pattern MGRS_SPACED_DIGITS = Pattern.compile(
            "^\\d{1,2}\\s*[A-Z]\\s*[A-Z]{2}\\s*(\\d+)\\s+(\\d+)$");

    // UTM: zone + band, easting, northing (whitespace separated)
    private static final Pattern UTM = Pattern.compile(
            "^(\\d{1,2})\\s*([C-HJ-NP-X])\\s+(\\d{6}(?:\\.\\d+)?)\\s*(?:M?E)?\\s+(\\d{1,8}(?:\\.\\d+)?)\\s*(?:M?N)?$");

    private static final Pattern TOKEN = Pattern.compile(
            "[NSEW]|[-+]?\\d+(?:\\.\\d+)?");

    // ---- public API -----------------------------------------------------

    /**
     * @return the parsed coordinate, or null if the text is not one
     */
    public static Result parse(String input) {
        if (input == null)
            return null;
        String s = input.trim();
        if (s.isEmpty() || s.length() > 512)
            return null;

        Result r = parseLink(s);
        if (r != null)
            return r;

        String upper = s.toUpperCase(Locale.ROOT)
                .replace("‎", "")
                .replace("‏", "");

        r = parseMgrs(upper);
        if (r != null)
            return r;

        r = parseUtm(upper);
        if (r != null)
            return r;

        return parseLatLon(upper);
    }

    // ---- map links ------------------------------------------------------

    private static Result parseLink(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        boolean isLink = lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("geo:")
                || lower.contains("maps.google")
                || lower.contains("google.com/maps")
                || lower.contains("maps.apple.com")
                || lower.contains("openstreetmap.org");
        if (!isLink)
            return null;

        Matcher m = LINK_AT.matcher(s);
        if (!m.find()) {
            m = LINK_PAIR.matcher(s);
            if (!m.find())
                return null;
        }
        double lat = Double.parseDouble(m.group(1));
        double lon = Double.parseDouble(m.group(2));
        if (!validLatLon(lat, lon))
            return null;
        return new Result(lat, lon, Format.LINK);
    }

    // ---- MGRS -----------------------------------------------------------

    private static Result parseMgrs(String upper) {
        // when easting/northing are typed as separate groups they must be
        // the same length, otherwise the precision is ambiguous
        Matcher spaced = MGRS_SPACED_DIGITS.matcher(upper.trim());
        if (spaced.matches()
                && spaced.group(1).length() != spaced.group(2).length())
            return null;

        String compact = upper.replaceAll("\\s+", "");
        Matcher m = MGRS.matcher(compact);
        if (!m.matches())
            return null;

        int zone = Integer.parseInt(m.group(1));
        String digits = m.group(5);
        if (zone < 1 || zone > 60 || digits.length() % 2 != 0)
            return null;

        try {
            MGRSPoint p = MGRSPoint.decodeString(compact, Ellipsoid.WGS_84,
                    null);
            double[] ll = p.toLatLng(null);
            if (ll == null || !validLatLon(ll[0], ll[1]))
                return null;
            return new Result(ll[0], ll[1], Format.MGRS);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- UTM ------------------------------------------------------------

    private static Result parseUtm(String upper) {
        Matcher m = UTM.matcher(upper.trim());
        if (!m.matches())
            return null;
        int zone = Integer.parseInt(m.group(1));
        if (zone < 1 || zone > 60)
            return null;
        String zone3 = String.format(Locale.US, "%02d%s", zone, m.group(2));
        try {
            UTMPoint p = UTMPoint.decodeString(zone3, m.group(3), m.group(4));
            if (p == null)
                return null;
            double[] ll = p.toLatLng(null);
            if (ll == null || !validLatLon(ll[0], ll[1]))
                return null;
            return new Result(ll[0], ll[1], Format.UTM);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- DD / DM / DMS --------------------------------------------------

    private static final class Tok {
        final String text;
        final boolean hemi;

        Tok(String text) {
            this.text = text;
            this.hemi = Character.isLetter(text.charAt(0));
        }
    }

    private static Result parseLatLon(String upper) {
        String s = upper
                // labels people paste along with the numbers
                .replaceAll("\\b(LATITUDE|LONGITUDE|LAT|LON|LNG|LONG)\\b\\s*[:=]?", " ")
                // degree, minute and second marks of every flavor
                .replaceAll("[°º˚∘′’‘'`´″”“\"]", " ")
                // separators
                .replaceAll("[,;/|()\\[\\]]", " ")
                .trim();
        if (s.isEmpty())
            return null;

        // tokenize, and insist that nothing but whitespace sits between
        // tokens; any stray letter means this is not a coordinate
        List<Tok> toks = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        int last = 0;
        while (m.find()) {
            if (!s.substring(last, m.start()).trim().isEmpty())
                return null;
            toks.add(new Tok(m.group()));
            last = m.end();
        }
        if (!s.substring(last).trim().isEmpty() || toks.isEmpty())
            return null;

        List<Tok> hemis = new ArrayList<>();
        for (Tok t : toks)
            if (t.hemi)
                hemis.add(t);

        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        char hemiA = 0, hemiB = 0;

        if (hemis.isEmpty()) {
            int n = toks.size();
            if (n != 2 && n != 4 && n != 6)
                return null;
            for (int i = 0; i < n; i++)
                (i < n / 2 ? a : b).add(toks.get(i).text);
        } else if (hemis.size() == 2) {
            boolean prefix = toks.get(0).hemi;
            int section = 0; // 0 = first group, 1 = second group
            for (int i = 0; i < toks.size(); i++) {
                Tok t = toks.get(i);
                if (t.hemi) {
                    if (prefix) {
                        if (hemiA == 0) {
                            hemiA = t.text.charAt(0);
                            section = 0;
                        } else {
                            hemiB = t.text.charAt(0);
                            section = 1;
                        }
                    } else {
                        if (hemiA == 0) {
                            hemiA = t.text.charAt(0);
                            section = 1;
                        } else {
                            hemiB = t.text.charAt(0);
                            section = 2; // nothing may follow
                        }
                    }
                } else {
                    if (section == 0)
                        a.add(t.text);
                    else if (section == 1)
                        b.add(t.text);
                    else
                        return null;
                }
            }
        } else {
            return null;
        }

        if (a.isEmpty() || b.isEmpty() || a.size() > 3 || b.size() > 3)
            return null;

        Double va = toDegrees(a);
        Double vb = toDegrees(b);
        if (va == null || vb == null)
            return null;
        double lat = va, lon = vb;

        if (hemiA != 0) {
            boolean aIsLat = hemiA == 'N' || hemiA == 'S';
            boolean bIsLat = hemiB == 'N' || hemiB == 'S';
            if (aIsLat == bIsLat)
                return null; // e.g. "N ... S ..."
            // a sign plus a hemisphere letter is contradictory
            if (a.get(0).startsWith("-") || b.get(0).startsWith("-"))
                return null;
            if (hemiA == 'S' || hemiA == 'W')
                va = -va;
            if (hemiB == 'S' || hemiB == 'W')
                vb = -vb;
            lat = aIsLat ? va : vb;
            lon = aIsLat ? vb : va;
        } else if (Math.abs(lat) > 90 && Math.abs(lon) <= 90
                && Math.abs(lat) <= 180) {
            // unambiguous lon,lat order (e.g. GeoJSON): swap
            double t = lat;
            lat = lon;
            lon = t;
        }

        if (!validLatLon(lat, lon))
            return null;

        int parts = Math.max(a.size(), b.size());
        Format f = parts == 1 ? Format.DD
                : parts == 2 ? Format.DM : Format.DMS;
        return new Result(lat, lon, f);
    }

    /**
     * [deg], [deg, min] or [deg, min, sec] to signed decimal degrees.
     * Only the last part may have a fraction, and minutes/seconds must
     * be under 60.
     */
    private static Double toDegrees(List<String> parts) {
        try {
            String d = parts.get(0);
            boolean negative = d.startsWith("-");
            double deg = Math.abs(Double.parseDouble(d));
            double total = deg;
            for (int i = 1; i < parts.size(); i++) {
                String p = parts.get(i);
                if (p.startsWith("-") || p.startsWith("+"))
                    return null;
                double v = Double.parseDouble(p);
                if (v >= 60)
                    return null;
                total += v / (i == 1 ? 60.0 : 3600.0);
            }
            for (int i = 0; i < parts.size() - 1; i++)
                if (parts.get(i).contains("."))
                    return null;
            return negative ? -total : total;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean validLatLon(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }
}
