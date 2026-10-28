package com.atakmap.android.quicksearch.plugin.core;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an imported feature's attributes (a KML/KMZ placemark's description
 * and ExtendedData, a shapefile's columns, ...) into plain searchable text.
 *
 * No Android dependencies, so it can be unit tested on a plain JVM.
 */
public final class FeatureText {

    /** Longest description text kept per feature, in characters. */
    public static final int MAX_CHARS = 2000;

    /**
     * Attribute keys that only carry KML/OGR structure, not words anyone
     * would search for. Compared lowercase.
     */
    private static final Set<String> STRUCTURAL = new HashSet<>();
    static {
        String[] keys = {
                "timestamp", "begin", "end", "altitudemode", "tessellate",
                "extrude", "visibility", "draworder", "icon", "styleurl",
                "ogr_style", "id", "fid", "ogc_fid", "objectid", "shape_area",
                "shape_leng", "shape_length", "gml_id"
        };
        for (String k : keys)
            STRUCTURAL.add(k);
    }

    private static final Pattern SCRIPT_STYLE = Pattern.compile(
            "(?is)<(script|style)[^>]*>.*?</\\1\\s*>");
    private static final Pattern CDATA = Pattern.compile("(?s)<!\\[CDATA\\[|\\]\\]>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern NUM_ENTITY = Pattern
            .compile("&#(x[0-9a-fA-F]+|[0-9]+);");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private FeatureText() {
    }

    /**
     * The name to show for a feature: its own name, else a "name" attribute
     * the way ATAK looks for one, else null.
     */
    public static String displayName(String featureName,
            Map<String, String> attrs) {
        if (featureName != null && !featureName.trim().isEmpty())
            return featureName.trim();
        if (attrs == null)
            return null;
        for (String key : new String[] {
                "name", "Name", "NAME"
        }) {
            String v = attrs.get(key);
            if (v != null && !v.trim().isEmpty())
                return stripHtml(v);
        }
        return null;
    }

    /**
     * Description and data values joined into one string, HTML removed,
     * capped at {@link #MAX_CHARS}. Keys are left out; values are what
     * people search for.
     *
     * @param attrs attribute name to value, in display order; may be null
     */
    public static String searchableText(Map<String, String> attrs) {
        if (attrs == null || attrs.isEmpty())
            return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            String key = e.getKey() == null ? ""
                    : e.getKey().toLowerCase(Locale.ROOT);
            if (STRUCTURAL.contains(key) || isNameKey(key))
                continue;
            String v = e.getValue();
            if (v == null)
                continue;
            v = stripHtml(v);
            if (v.isEmpty())
                continue;
            if (sb.length() > 0)
                sb.append(' ');
            sb.append(v);
            if (sb.length() >= MAX_CHARS)
                break;
        }
        return sb.length() > MAX_CHARS ? sb.substring(0, MAX_CHARS)
                : sb.toString();
    }

    private static boolean isNameKey(String lowerKey) {
        return lowerKey.equals("name") || lowerKey.startsWith("name:")
                || lowerKey.startsWith("name_");
    }

    /** Remove tags, scripts and CDATA markers; decode common entities. */
    public static String stripHtml(String s) {
        if (s == null || s.isEmpty())
            return "";
        String t = s;
        if (t.indexOf('<') >= 0) {
            t = SCRIPT_STYLE.matcher(t).replaceAll(" ");
            t = CDATA.matcher(t).replaceAll("");
            t = TAG.matcher(t).replaceAll(" ");
        }
        if (t.indexOf('&') >= 0)
            t = decodeEntities(t);
        return SPACES.matcher(t).replaceAll(" ").trim();
    }

    private static String decodeEntities(String t) {
        Matcher m = NUM_ENTITY.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String code = m.group(1);
            int cp;
            try {
                cp = code.startsWith("x") || code.startsWith("X")
                        ? Integer.parseInt(code.substring(1), 16)
                        : Integer.parseInt(code);
            } catch (NumberFormatException e) {
                cp = ' ';
            }
            String rep = Character.isValidCodePoint(cp)
                    ? new String(Character.toChars(cp))
                    : " ";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString()
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
    }
}
