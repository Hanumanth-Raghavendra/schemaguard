package io.github.hanumanthraghavendra.schemaguard.engine.model;

/** How durable a table is. */
public enum Persistence {
    PERMANENT,
    UNLOGGED,
    TEMPORARY
}
