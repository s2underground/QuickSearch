package com.atakmap.android.quicksearch.plugin.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class FeatureTextTest {

    private static Map<String, String> attrs(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
            m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void stripsHtmlFromKmlDescriptions() {
        String html = "<![CDATA[<html><body><table border=\"1\">"
                + "<tr><td>Division</td><td>Alpha</td></tr>"
                + "<tr><td>Water&nbsp;Source</td><td>Hydrant &amp; tank</td></tr>"
                + "</table><script>var x=1;</script></body></html>]]>";
        assertEquals("Division Alpha Water Source Hydrant & tank",
                FeatureText.stripHtml(html));
    }

    @Test
    public void decodesNumericEntities() {
        assertEquals("Café – north", FeatureText.stripHtml("Caf&#233; &#x2013; north"));
        assertEquals("a < b", FeatureText.stripHtml("a &lt; b"));
    }

    @Test
    public void plainTextIsLeftAlone() {
        assertEquals("Staging area 3", FeatureText.stripHtml("  Staging   area 3 "));
        assertEquals("", FeatureText.stripHtml(null));
    }

    @Test
    public void searchableTextKeepsValuesAndSkipsStructure() {
        String text = FeatureText.searchableText(attrs(
                "Name", "Drop Point 4",
                "Description", "<b>Helispot</b> near the ridge",
                "altitudeMode", "clampToGround",
                "tessellate", "-1",
                "Division", "Bravo",
                "OGR_STYLE", "SYMBOL(c:#ff0000)"));
        assertEquals("Helispot near the ridge Bravo", text);
    }

    @Test
    public void searchableTextIsCapped() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1000; i++)
            big.append("word").append(i).append(' ');
        String text = FeatureText.searchableText(attrs("Description",
                big.toString()));
        assertTrue(text.length() <= FeatureText.MAX_CHARS);
        assertTrue(text.startsWith("word0 word1"));
    }

    @Test
    public void displayNameFallsBackToNameAttribute() {
        assertEquals("Pond", FeatureText.displayName("Pond", null));
        assertEquals("Pond", FeatureText.displayName("  ", attrs("NAME", "Pond")));
        assertNull(FeatureText.displayName(null, attrs("Description", "x")));
    }

    @Test
    public void featureDescriptionsAreSearchable() {
        // end to end with the scorer: a keyword only in the description
        SearchScorer.Doc d = SearchScorer.doc(new String[] {
                "Placemark 17",
                FeatureText.searchableText(attrs("Description",
                        "<p>Dozer line <i>complete</i>; hydrant at gate</p>")),
                "kml point", "Incident.kmz › Ops"
        });
        assertTrue(SearchScorer.score(SearchScorer.prepare("hydrant"), d) > 0);
        assertTrue(SearchScorer.score(SearchScorer.prepare("dozer complete"), d) > 0);
        assertTrue(SearchScorer.score(SearchScorer.prepare("ops placemark"), d) > 0);
        assertEquals(0, SearchScorer.score(SearchScorer.prepare("helispot"), d));
    }
}
