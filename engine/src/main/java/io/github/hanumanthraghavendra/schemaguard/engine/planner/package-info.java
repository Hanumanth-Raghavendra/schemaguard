/**
 * The migration planner: from a diff to the SQL that applies it.
 *
 * <p>Start at {@link io.github.hanumanthraghavendra.schemaguard.engine.planner.Planner}. It
 * writes either the direct route, one plain statement per change, or the safe route, which
 * reaches the same schema with statements that do not block the application for long.
 */
package io.github.hanumanthraghavendra.schemaguard.engine.planner;
