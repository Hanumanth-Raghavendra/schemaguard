package io.github.hanumanthraghavendra.schemaguard.engine.model;

/** What a foreign key does when the row it references is deleted or updated. */
public enum ReferentialAction {
    NO_ACTION,
    RESTRICT,
    CASCADE,
    SET_NULL,
    SET_DEFAULT
}
