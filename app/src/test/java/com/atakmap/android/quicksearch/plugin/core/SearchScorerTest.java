package com.atakmap.android.quicksearch.plugin.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.quicksearch.plugin.core.SearchScorer.Query;

import org.junit.Test;

public class SearchScorerTest {

    /** name, remarks, type, group */
    private static int score(String q, String name, String remarks,
            String type, String group) {
        Query query = SearchScorer.prepare(q);
        return SearchScorer.score(query, new String[] {
                name, remarks, type, group
        });
    }

    private static int name(String q, String name) {
        return score(q, name, null, null, null);
    }

    @Test
    public void emptyQueryMatchesNothing() {
        assertEquals(0, name("", "Alpha"));
        assertEquals(0, name("   ", "Alpha"));
    }

    @Test
    public void basicMatchesAreCaseAndPunctuationInsensitive() {
        assertTrue(name("alpha", "ALPHA") > 0);
        assertTrue(name("bravo2", "Bravo-2") > 0);
        assertTrue(name("bravo 2", "Bravo-2") > 0);
        assertTrue(name("cafe", "Café Rally") > 0);
    }

    @Test
    public void allWordsMustMatch() {
        assertTrue(name("fire 2", "Fire Line 2") > 0);
        assertEquals(0, name("fire 2", "Water 2"));
        assertEquals(0, name("zulu", "Alpha"));
    }

    @Test
    public void wordsCanMatchDifferentFields() {
        assertTrue(score("hawk water", "LZ Hawk", "water resupply here",
                null, null) > 0);
        assertTrue(score("medic", "Bravo 6", null, null, "Medic Team")
                > 0);
    }

    @Test
    public void closerMatchesRankHigher() {
        int exact = name("rally", "Rally");
        int prefix = name("rally", "Rally Point North");
        int word = name("rally", "North Rally Point");
        int contains = name("ally", "North Rally Point");
        assertTrue(exact > prefix);
        assertTrue(prefix > word);
        assertTrue(word > contains);
        assertTrue(contains > 0);
    }

    @Test
    public void nameOutranksRemarks() {
        int inName = score("water", "Water Point", null, null, null);
        int inRemarks = score("water", "CP 3", "near the water tower",
                null, null);
        assertTrue(inName > inRemarks);
    }

    @Test
    public void toleratesOneTypoOnLongerWords() {
        assertTrue(name("hlo", "HLZ") == 0); // too short for fuzziness
        assertTrue(name("checkpiont", "Checkpoint 4") > 0);
        assertTrue(name("chekpoint", "Checkpoint 4") > 0);
        assertTrue(name("checkpoint", "Chekpoint 4") > 0);
        assertEquals(0, name("chckpnt", "Checkpoint 4"));
    }

    @Test
    public void digitsSplitFromLetters() {
        assertTrue(name("12", "Truck12") > 0);
        assertTrue(name("truck", "Truck12") > 0);
    }

    @Test
    public void editDistanceHelper() {
        assertTrue(SearchScorer.withinOneEdit("abcd", "abcd"));
        assertTrue(SearchScorer.withinOneEdit("abcd", "abxd"));
        assertTrue(SearchScorer.withinOneEdit("abcd", "abd"));
        assertTrue(SearchScorer.withinOneEdit("abd", "abcd"));
        assertTrue(SearchScorer.withinOneEdit("abcd", "abcde"));
        assertTrue(!SearchScorer.withinOneEdit("abcd", "axyd"));
        assertTrue(!SearchScorer.withinOneEdit("abcd", "ab"));
    }

    @Test
    public void scanningMatchesWordListLevels() {
        String[] fields = {
                "near the water tower", "alpha1 staging", "bravo",
                "tower", "lzhawk 7", "x"
        };
        String[] words = { "water", "wat", "ater", "tower", "1", "alpha",
                "staging", "hawk", "7", "zulu" };
        for (String f : fields) {
            String n = SearchScorer.normalize(f);
            for (String w : words) {
                int list = SearchScorer.wordScore(w, n);
                int scan = SearchScorer.scanWordScore(w, n);
                // whole-field equals/starts-with (100/80) are checked before
                // either path runs; the word-list path also tries fuzzy and
                // joined forms, which scanning skips. Below that they agree.
                if (list < 80 && (list >= 40 || scan > 0))
                    assertEquals(f + " / " + w, list, scan);
            }
        }
    }

    @Test
    public void preparedDocScoresLikeRawFields() {
        String[] f = { "Rally Point North", "water resupply at gate 3",
                "b-m-p-s-m spot", "User Objects" };
        SearchScorer.Doc d = SearchScorer.doc(f);
        for (String q : new String[] { "rally", "gate 3", "water rally",
                "spot", "objects", "zulu" }) {
            SearchScorer.Query query = SearchScorer.prepare(q);
            assertEquals(q, SearchScorer.score(query, f),
                    SearchScorer.score(query, d));
        }
    }
}
