package io.github.hanumanthraghavendra.schemaguard.engine.source;

/**
 * A half-open range {@code [startOffset, endOffset)} in the source text, plus the
 * 1-based line and column of its first character.
 *
 * <p>Offsets and columns count UTF-16 code units, matching {@link String#charAt(int)}.
 */
public record SourceSpan(int startOffset, int endOffset, int line, int column) {

    public SourceSpan {
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException(
                    "Invalid span offsets: [" + startOffset + ", " + endOffset + ")");
        }
        if (line < 1 || column < 1) {
            throw new IllegalArgumentException(
                    "Line and column are 1-based, got " + line + ":" + column);
        }
    }

    public int length() {
        return endOffset - startOffset;
    }
}
