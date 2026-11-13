package io.github.hanumanthraghavendra.schemaguard.engine.diff;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * How alike two names are, from 0 (nothing in common) to 1 (the same), with a short reason a
 * person can check. Used to rank possible renames.
 *
 * <p>Edit distance alone misjudges the renames people actually make: {@code email} to
 * {@code email_address} or {@code qty} to {@code quantity} are far apart in edits but plainly
 * related. So the score is the best of three measures, each with a fixed value that is easy to
 * explain.
 */
final class Similarity {

    /** One name's underscore-separated words all appear in the other: {@code name} and {@code full_name}. */
    static final double CONTAINS = 0.8;

    /** The shorter name's letters appear in order in the longer one, from the same first letter: {@code qty} and {@code quantity}. */
    static final double ABBREVIATES = 0.6;

    record Score(double value, String explanation) {}

    private Similarity() {}

    static Score of(String a, String b) {
        if (a.equals(b)) {
            return new Score(1.0, "same name");
        }
        String x = a.toLowerCase(Locale.ROOT);
        String y = b.toLowerCase(Locale.ROOT);
        if (x.equals(y)) {
            return new Score(1.0, "names differ only in letter case");
        }
        boolean xIsLonger = x.length() >= y.length();
        String longer = xIsLonger ? x : y;
        String shorter = xIsLonger ? y : x;

        double edit = 1.0 - (double) distance(x, y) / longer.length();
        Score best = new Score(edit, "names " + Math.round(edit * 100) + "% similar");
        if (CONTAINS > best.value() && words(longer).containsAll(words(shorter))) {
            best = new Score(CONTAINS, "one name contains the other");
        }
        if (ABBREVIATES > best.value() && abbreviates(shorter, longer)) {
            best = new Score(ABBREVIATES, "one name abbreviates the other");
        }
        return best;
    }

    /** Levenshtein distance: the fewest single-character insertions, deletions and substitutions. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static HashSet<String> words(String name) {
        List<String> words = Arrays.stream(name.split("_")).filter(w -> !w.isEmpty()).toList();
        return new HashSet<>(words);
    }

    private static boolean abbreviates(String shorter, String longer) {
        if (shorter.length() < 3 || shorter.charAt(0) != longer.charAt(0)) {
            return false;
        }
        int found = 0;
        for (int i = 0; i < longer.length() && found < shorter.length(); i++) {
            if (longer.charAt(i) == shorter.charAt(found)) {
                found++;
            }
        }
        return found == shorter.length();
    }
}
