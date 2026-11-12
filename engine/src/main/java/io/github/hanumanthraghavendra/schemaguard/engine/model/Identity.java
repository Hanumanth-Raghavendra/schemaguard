package io.github.hanumanthraghavendra.schemaguard.engine.model;

/** Whether a column is an identity column, and of which kind. */
public enum Identity {
    NONE,
    ALWAYS,
    BY_DEFAULT
}
