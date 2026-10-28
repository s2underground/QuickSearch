package com.atakmap.android.quicksearch.plugin.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.atakmap.android.quicksearch.plugin.core.CoordinateParser.Format;
import com.atakmap.android.quicksearch.plugin.core.CoordinateParser.Result;

import org.junit.Test;

/**
 * Reference values for MGRS/UTM were produced independently with the
 * GeoTrans-based "mgrs" library and PROJ, not with ATAK's own converter.
 */
public class CoordinateParserTest {

    // MGRS at 1 m precision lands on the SW corner of the 1 m square
    private static final double GRID_TOL = 3e-5;
    private static final double TOL = 1e-6;

    private static Result ok(String in, Format f) {
        Result r = CoordinateParser.parse(in);
        assertNotNull("should parse: " + in, r);
        assertEquals("format of: " + in, f, r.format);
        return r;
    }

    private static void at(Result r, double lat, double lon, double tol) {
        assertEquals("lat", lat, r.latitude, tol);
        assertEquals("lon", lon, r.longitude, tol);
    }

    // ---- MGRS -----------------------------------------------------------

    @Test
    public void mgrsCompact() {
        at(ok("17SNU1427798239", Format.MGRS), 35.2271, -80.8431, GRID_TOL);
    }

    @Test
    public void mgrsSpacedAndLowercase() {
        at(ok("17S NU 14277 98239", Format.MGRS), 35.2271, -80.8431,
                GRID_TOL);
        at(ok("17snu 14277 98239", Format.MGRS), 35.2271, -80.8431,
                GRID_TOL);
        at(ok("  18S UJ 23478 06483 ", Format.MGRS), 38.8895, -77.0353,
                GRID_TOL);
    }

    @Test
    public void mgrsSouthernAndSingleDigitZone() {
        at(ok("56HLH3490052288", Format.MGRS), -33.8568, 151.2153,
                GRID_TOL);
        at(ok("4QFJ1841756542", Format.MGRS), 21.3069, -157.8583, GRID_TOL);
        at(ok("04Q FJ 18417 56542", Format.MGRS), 21.3069, -157.8583,
                GRID_TOL);
        at(ok("6WVS6601290570", Format.MGRS), 64.8378, -147.7164, GRID_TOL);
    }

    @Test
    public void mgrsNearEquatorAndDateline() {
        at(ok("30NYF7826555318", Format.MGRS), 0.5, -0.5, GRID_TOL);
        at(ok("60MYE7826544681", Format.MGRS), -0.5, 179.5, GRID_TOL);
    }

    @Test
    public void mgrsLowerPrecision() {
        // 10 m (4+4) and 100 m (3+3) precision
        at(ok("17SNU14279823", Format.MGRS), 35.2271, -80.8431, 2e-4);
        at(ok("17S NU 142 982", Format.MGRS), 35.2271, -80.8431, 2e-3);
    }

    @Test
    public void mgrsRejectsBadInput() {
        assertNull(CoordinateParser.parse("17SNU142779823")); // odd digits
        assertNull(CoordinateParser.parse("17S NU 1427 798239")); // uneven
        assertNull(CoordinateParser.parse("17SIU1427798239")); // I not used
        assertNull(CoordinateParser.parse("61SNU1427798239")); // zone > 60
        assertNull(CoordinateParser.parse("17ANU1427798239")); // bad band
    }

    // ---- UTM ------------------------------------------------------------

    @Test
    public void utm() {
        at(ok("17S 514278 3898239", Format.UTM), 35.2271, -80.8431, 3e-5);
        at(ok("17S 514278mE 3898239mN", Format.UTM), 35.2271, -80.8431,
                3e-5);
        at(ok("56H 334901 6252289", Format.UTM), -33.8568, 151.2153, 3e-5);
    }

    // ---- decimal degrees -----------------------------------------------

    @Test
    public void decimalDegreesPlain() {
        at(ok("35.2271, -80.8431", Format.DD), 35.2271, -80.8431, TOL);
        at(ok("35.2271 -80.8431", Format.DD), 35.2271, -80.8431, TOL);
        at(ok("35.2271,-80.8431", Format.DD), 35.2271, -80.8431, TOL);
        at(ok("+35.2271 , -80.8431", Format.DD), 35.2271, -80.8431, TOL);
        at(ok("(35.2271, -80.8431)", Format.DD), 35.2271, -80.8431, TOL);
        at(ok("-33.8568, 151.2153", Format.DD), -33.8568, 151.2153, TOL);
    }

    @Test
    public void decimalDegreesWithHemispheres() {
        at(ok("35.2271N 80.8431W", Format.DD), 35.2271, -80.8431, TOL);
        at(ok("35.2271° N, 80.8431° W", Format.DD), 35.2271, -80.8431, TOL);
        at(ok("N35.2271 W80.8431", Format.DD), 35.2271, -80.8431, TOL);
        at(ok("N 35.2271, W 80.8431", Format.DD), 35.2271, -80.8431, TOL);
        at(ok("35.2271n 80.8431w", Format.DD), 35.2271, -80.8431, TOL);
        at(ok("33.8568S 151.2153E", Format.DD), -33.8568, 151.2153, TOL);
    }

    @Test
    public void decimalDegreesLongitudeFirstWithLetters() {
        at(ok("80.8431W 35.2271N", Format.DD), 35.2271, -80.8431, TOL);
        at(ok("W80.8431 N35.2271", Format.DD), 35.2271, -80.8431, TOL);
    }

    @Test
    public void decimalDegreesWithLabels() {
        at(ok("lat: 35.2271, lon: -80.8431", Format.DD), 35.2271, -80.8431,
                TOL);
        at(ok("Latitude 35.2271 Longitude -80.8431", Format.DD), 35.2271,
                -80.8431, TOL);
    }

    @Test
    public void unambiguousLonLatIsSwapped() {
        // GeoJSON order; -80 can't be a latitude... but 151 can't either
        at(ok("151.2153, -33.8568", Format.DD), -33.8568, 151.2153, TOL);
    }

    // ---- degrees minutes / degrees minutes seconds ----------------------

    @Test
    public void degreesMinutes() {
        Result r = ok("N35 13.626 W080 50.586", Format.DM);
        at(r, 35 + 13.626 / 60, -(80 + 50.586 / 60), TOL);
        r = ok("35°13.626'N 80°50.586'W", Format.DM);
        at(r, 35 + 13.626 / 60, -(80 + 50.586 / 60), TOL);
        r = ok("35 13.626, -80 50.586", Format.DM);
        at(r, 35 + 13.626 / 60, -(80 + 50.586 / 60), TOL);
    }

    @Test
    public void degreesMinutesSeconds() {
        double lat = 35 + 13 / 60.0 + 37.6 / 3600;
        double lon = -(80 + 50 / 60.0 + 35.2 / 3600);
        at(ok("35°13'37.6\"N 80°50'35.2\"W", Format.DMS), lat, lon, TOL);
        at(ok("35°13′37.6″N 80°50′35.2″W", Format.DMS), lat, lon, TOL);
        at(ok("35 13 37.6 N 80 50 35.2 W", Format.DMS), lat, lon, TOL);
        at(ok("N 35 13 37.6 W 80 50 35.2", Format.DMS), lat, lon, TOL);
        at(ok("35 13 37.6 -80 50 35.2", Format.DMS), lat, lon, TOL);
    }

    @Test
    public void minutesAndSecondsMustBeValid() {
        assertNull(CoordinateParser.parse("35 61 N 80 50 W"));
        assertNull(CoordinateParser.parse("35 13 61 N 80 50 35 W"));
        assertNull(CoordinateParser.parse("35.5 13 N 80 50 W")); // frac deg
        assertNull(CoordinateParser.parse("35 -13 N 80 50 W"));
    }

    // ---- links ----------------------------------------------------------

    @Test
    public void mapLinks() {
        at(ok("https://www.google.com/maps/@35.2271,-80.8431,15z",
                Format.LINK), 35.2271, -80.8431, TOL);
        at(ok("https://maps.google.com/?q=35.2271,-80.8431", Format.LINK),
                35.2271, -80.8431, TOL);
        at(ok("https://www.google.com/maps/search/?api=1&query=35.2271%2C-80.8431",
                Format.LINK), 35.2271, -80.8431, TOL);
        at(ok("https://maps.apple.com/?ll=35.2271,-80.8431&q=Pin",
                Format.LINK), 35.2271, -80.8431, TOL);
        at(ok("geo:35.2271,-80.8431?z=12", Format.LINK), 35.2271, -80.8431,
                TOL);
    }

    // ---- things that are not coordinates --------------------------------

    @Test
    public void notCoordinates() {
        String[] notCoords = {
                null, "", "   ", "Alpha 1", "fire", "rally point",
                "123", "12345", "Bravo 2 N", "N", "S 1", "E7", "LZ Hawk",
                "N 35 S 80", "-35 N 80 W", "95, 200", "Truck 4 at gate",
                "https://example.com/page", "35.2271"
        };
        for (String s : notCoords)
            assertNull("should not parse: " + s, CoordinateParser.parse(s));
    }

    @Test
    public void outOfRangeRejected() {
        assertNull(CoordinateParser.parse("91, 100"));
        assertNull(CoordinateParser.parse("10, 181"));
        assertNull(CoordinateParser.parse("-91.5, 200"));
        assertNull(CoordinateParser.parse("91N 10E"));
    }
}
