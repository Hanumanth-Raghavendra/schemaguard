package io.github.hanumanthraghavendra.schemaguard.engine.model;

/** When a constraint is checked: at the end of each statement, or at commit. */
public enum Deferrability {
    NOT_DEFERRABLE,
    DEFERRABLE_INITIALLY_IMMEDIATE,
    DEFERRABLE_INITIALLY_DEFERRED
}
