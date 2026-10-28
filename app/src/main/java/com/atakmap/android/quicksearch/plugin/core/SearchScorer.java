package com.atakmap.android.quicksearch.plugin.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Ranks how well an item's text fields match what the user typed.
 *
 * Every word the user types must match at least one field (AND search), so
 * "fire 2" finds "Fire Line 2" but not "Water 2". Closer matches score
 * higher: exact > starts-with > word starts-with > contains > typo-tolerant.
 * The name field is weighted above remarks/description, type and group.
 *
 * Fields can be prepared once with {@link #doc(String[])} and scored many
 * times; imported files can hold tens of thousands of features, so the
 * expensive normalization must not happen on every keystroke.
 *
 * No Android dependencies, so it can be unit tested on a plain JVM.
 */
public final class SearchScorer {

    /** How much a match in each field counts. */
    public enum Field {
        NAME(5), REMARKS(2), TYPE(1), GROUP(1);

        final int weight;

        Field(int weight) {
            this.weight = weight;
        }
    }

    private static final Pattern ACCENTS = Pattern.compile("\\p{M}");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern LETTER_DIGIT = Pattern
            .compile("(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})| ");
    private static final String[] NO_WORDS = new String[0];

    private SearchScorer() {
    }

    /** A query split into normalized words, ready to score many items. */
    public static final class Query {
        final String whole;
        final String[] words;

        Query(String whole, String[] words) {
            this.whole = whole;
            this.words = words;
        }

        public boolean isEmpty() {
            return words.length == 0;
        }
    }

    /**
     * An item's fields, normalized and split into words once. Long
     * description text is kept as one string (no word list) to save memory;
     * it is matched by scanning instead.
     */
    public static final class Doc {
        final String[] text; // normalized, indexed by Field.ordinal()
        final String[][] words; // word lists; null for REMARKS

        Doc(String[] text, String[][] words) {
            this.text = text;
            this.words = words;
        }
    }

    public static Query prepare(String raw) {
        String n = normalize(raw);
        if (n.isEmpty())
            return new Query("", NO_WORDS);
        return new Query(n, n.split(" "));
    }

    /** @param fields values indexed by {@link Field#ordinal()}; nulls allowed */
    public static Doc doc(String[] fields) {
        int n = Field.values().length;
        String[] text = new String[n];
        String[][] words = new String[n][];
        for (int i = 0; i < n; i++) {
            String v = i < fields.length ? fields[i] : null;
            text[i] = normalize(v);
            words[i] = i == Field.REMARKS.ordinal() ? null
                    : splitWords(text[i]);
        }
        return new Doc(text, words);
    }

    /**
     * @param fields values indexed by {@link Field#ordinal()}; nulls allowed
     * @return 0 if the item does not match, otherwise a positive score
     */
    public static int score(Query q, String[] fields) {
        return score(q, doc(fields));
    }

    /** @return 0 if the item does not match, otherwise a positive score */
    public static int score(Query q, Doc d) {
        if (q.isEmpty())
            return 0;

        int total = 0;
        for (String w : q.words) {
            int best = 0;
            for (Field f : Field.values()) {
                int i = f.ordinal();
                // typo tolerance only on names: descriptions can be long,
                // and fuzzy matching every word of them is slow and noisy
                int s = wordScore(w, d.text[i], d.words[i],
                        f == Field.NAME) * f.weight;
                if (s > best)
                    best = s;
            }
            if (best == 0)
                return 0; // every word has to hit something
            total += best;
        }

        // the whole query matching the name outright beats everything
        String name = d.text[Field.NAME.ordinal()];
        if (!name.isEmpty()) {
            if (name.equals(q.whole))
                total += 1000;
            else if (name.startsWith(q.whole))
                total += 300;
        }
        return total;
    }

    static int wordScore(String w, String field) {
        String n = field == null ? "" : field;
        return wordScore(w, n, splitWords(n), true);
    }

    private static int wordScore(String w, String field, String[] words,
            boolean fuzzy) {
        if (field.isEmpty() || w.isEmpty())
            return 0;
        if (field.equals(w))
            return 100;
        if (field.startsWith(w))
            return 80;
        if (words == null)
            return scanWordScore(w, field);

        for (String fw : words)
            if (fw.equals(w))
                return 70;
        for (String fw : words)
            if (fw.startsWith(w))
                return 60;
        if (field.contains(w))
            return 40;

        // tolerate one typo on words long enough for it to be meaningful
        if (fuzzy && w.length() >= 4) {
            for (String fw : words) {
                if (withinOneEdit(w, fw))
                    return 20;
                if (fw.length() > w.length()
                        && withinOneEdit(w, fw.substring(0, w.length())))
                    return 15;
            }
        }
        return 0;
    }

    /**
     * Same levels as the word-list path (70 whole word, 60 word start, 40
     * anywhere) but found by scanning the text, with no word list stored.
     * Word boundaries are spaces and letter/digit changes ("alpha1").
     */
    static int scanWordScore(String w, String field) {
        int idx = field.indexOf(w);
        if (idx < 0)
            return 0;
        int best = 40;
        while (idx >= 0) {
            boolean starts = idx == 0
                    || boundary(field.charAt(idx - 1), w.charAt(0));
            if (starts) {
                int end = idx + w.length();
                boolean ends = end == field.length()
                        || boundary(field.charAt(end - 1),
                                field.charAt(end));
                if (ends)
                    return 70;
                best = 60;
            }
            idx = field.indexOf(w, idx + 1);
        }
        return best;
    }

    private static boolean boundary(char before, char after) {
        if (before == ' ' || after == ' ')
            return true;
        return (Character.isLetter(before) && Character.isDigit(after))
                || (Character.isDigit(before) && Character.isLetter(after));
    }

    /** Lowercase, strip accents, turn punctuation into spaces, collapse. */
    public static String normalize(String s) {
        if (s == null || s.isEmpty())
            return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        n = ACCENTS.matcher(n).replaceAll("").toLowerCase(Locale.ROOT);
        return NON_WORD.matcher(n).replaceAll(" ").trim();
    }

    /** @param field an already normalized field */
    private static String[] splitWords(String field) {
        if (field.isEmpty())
            return NO_WORDS;
        List<String> out = new ArrayList<>();
        for (String p : field.split(" "))
            if (!p.isEmpty())
                out.add(p);
        // also offer split points between letters and digits, so
        // "alpha1" can be found by "1" and "bravo-2" by "bravo2"
        for (String p : LETTER_DIGIT.split(field))
            if (!p.isEmpty() && !out.contains(p))
                out.add(p);
        // and the whole thing without spaces, for short names only
        if (out.size() <= 8) {
            String joined = field.replace(" ", "");
            if (!out.contains(joined))
                out.add(joined);
        }
        return out.toArray(NO_WORDS);
    }

    /**
     * True if a and b differ by at most one insert, delete, substitution,
     * or swap of two neighboring letters (the most common typo).
     */
    static boolean withinOneEdit(String a, String b) {
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > 1)
            return false;
        if (la == lb) {
            int first = -1, diffs = 0;
            for (int k = 0; k < la; k++) {
                if (a.charAt(k) != b.charAt(k)) {
                    if (first < 0)
                        first = k;
                    diffs++;
                }
            }
            if (diffs == 2 && first + 1 < la
                    && a.charAt(first) == b.charAt(first + 1)
                    && a.charAt(first + 1) == b.charAt(first))
                return true;
        }
        if (la > lb)
            return withinOneEdit(b, a);
        int i = 0, j = 0;
        boolean edited = false;
        while (i < la && j < lb) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (edited)
                return false;
            edited = true;
            if (la == lb)
                i++;
            j++;
        }
        return true;
    }
}
