package io.github.hanumanthraghavendra.schemaguard.cli;

import java.util.Locale;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * The value of {@code --fail-above}: a score from 0 to 100, or a level whose band the score must
 * stay within. Levels start at 1 (low), 25 (medium), 50 (high) and 75 (critical), so {@code
 * medium} fails at 50 and above, the same as {@code 49}.
 *
 * @param limit the highest score that passes
 * @param text  the value as written, for messages
 */
record Threshold(int limit, String text) {

    boolean exceeded(int score) {
        return score > limit;
    }

    static final class Converter implements ITypeConverter<Threshold> {
        @Override
        public Threshold convert(String value) {
            String text = value.trim().toLowerCase(Locale.ROOT);
            int limit =
                    switch (text) {
                        case "none" -> 0;
                        case "low" -> 24;
                        case "medium" -> 49;
                        case "high" -> 74;
                        case "critical" -> throw new TypeConversionException(
                                "nothing is above critical; use high to fail on critical risks only");
                        default -> number(text);
                    };
            return new Threshold(limit, text);
        }

        private static int number(String text) {
            if (!text.matches("\\d{1,3}") || Integer.parseInt(text) > 100) {
                throw new TypeConversionException(
                        "'" + text + "' is neither a score from 0 to 100 nor one of none, low, medium, high");
            }
            return Integer.parseInt(text);
        }
    }
}
