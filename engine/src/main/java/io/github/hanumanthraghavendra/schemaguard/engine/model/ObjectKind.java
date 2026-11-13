package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.Locale;

/** The kinds of schema object the engine models. */
public enum ObjectKind {
    TABLE,
    INDEX,
    TYPE,
    SEQUENCE;

    /** The SQL keyword for this kind, in lower case. */
    public String keyword() {
        return name().toLowerCase(Locale.ROOT);
    }
}
