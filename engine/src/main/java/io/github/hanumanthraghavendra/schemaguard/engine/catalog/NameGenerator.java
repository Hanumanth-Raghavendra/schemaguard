package io.github.hanumanthraghavendra.schemaguard.engine.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Reproduces the names PostgreSQL chooses for objects the user did not name: {@code t_pkey},
 * {@code t_a_b_key}, {@code t_a_fkey}, {@code t_a_check}, {@code t_a_idx}, {@code t_a_seq}.
 *
 * <p>The rules are those of {@code makeObjectName}, {@code ChooseRelationName} and
 * {@code ChooseIndexColumnNames} in the PostgreSQL source, checked against a real server
 * (see the golden fixtures). Lengths are counted in characters where the server counts bytes,
 * so names with non-ASCII characters near the length limit may differ.
 */
final class NameGenerator {

    /** PostgreSQL's NAMEDATALEN minus the terminating byte. */
    private static final int MAX_LENGTH = 63;

    private NameGenerator() {}

    /**
     * Builds {@code name1[_name2]_label}. When that is too long, the longer of the two names is
     * shortened one character at a time until it fits; the label is never shortened.
     *
     * @param name2 may be null
     */
    static String objectName(String name1, String name2, String label) {
        int overhead = label.length() + 1 + (name2 == null ? 0 : 1);
        int available = MAX_LENGTH - overhead;
        int length1 = name1.length();
        int length2 = name2 == null ? 0 : name2.length();
        while (length1 + length2 > available) {
            if (length1 > length2) {
                length1--;
            } else {
                length2--;
            }
        }
        StringBuilder name = new StringBuilder(name1.substring(0, length1));
        if (name2 != null) {
            name.append('_').append(name2, 0, length2);
        }
        return name.append('_').append(label).toString();
    }

    /** The first of {@code t_a_idx}, {@code t_a_idx1}, {@code t_a_idx2}, ... that is not taken. */
    static String unique(String name1, String name2, String label, Predicate<String> taken) {
        String candidate = objectName(name1, name2, label);
        for (int pass = 1; taken.test(candidate); pass++) {
            candidate = objectName(name1, name2, label + pass);
        }
        return candidate;
    }

    /** Joins column names with underscores, stopping once the result reaches the length limit. */
    static String joinColumnNames(List<String> names) {
        StringBuilder joined = new StringBuilder();
        for (String name : names) {
            if (!joined.isEmpty()) {
                joined.append('_');
            }
            joined.append(name, 0, Math.min(name.length(), MAX_LENGTH));
            if (joined.length() > MAX_LENGTH) {
                break;
            }
        }
        return joined.toString();
    }

    /** Numbers repeated names: {@code expr, expr, expr} becomes {@code expr, expr1, expr2}. */
    static List<String> distinct(List<String> names) {
        List<String> result = new ArrayList<>();
        for (String original : names) {
            String current = original;
            for (int i = 1; result.contains(current); i++) {
                String suffix = Integer.toString(i);
                int keep = Math.min(original.length(), MAX_LENGTH - suffix.length());
                current = original.substring(0, keep) + suffix;
            }
            result.add(current);
        }
        return result;
    }
}
