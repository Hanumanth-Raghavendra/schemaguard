package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.Optional;

/**
 * A table constraint. Column-level constraints are stored the same way as table-level ones,
 * as a constraint over that single column, so later stages handle one shape only.
 *
 * <p>Sealed, so a {@code switch} over constraints is checked by the compiler: adding a new
 * kind forces every such switch to handle it.
 */
public sealed interface Constraint permits PrimaryKey, Unique, ForeignKey, Check {

    /** The name given with {@code CONSTRAINT name}, or empty if PostgreSQL will choose one. */
    Optional<String> name();
}
