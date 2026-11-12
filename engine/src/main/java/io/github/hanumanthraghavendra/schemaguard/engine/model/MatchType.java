package io.github.hanumanthraghavendra.schemaguard.engine.model;

/** How a multi-column foreign key treats NULLs. */
public enum MatchType {
    SIMPLE,
    FULL,
    PARTIAL
}
