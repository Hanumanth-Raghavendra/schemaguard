/**
 * The risk engine: what each schema change does to a running database, and the rules that
 * turn a {@link io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff} into a
 * {@link io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskReport}.
 *
 * <p>Start at {@link io.github.hanumanthraghavendra.schemaguard.engine.rules.RiskAnalyzer}.
 * The facts the rules rely on are in
 * {@link io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation},
 * {@link io.github.hanumanthraghavendra.schemaguard.engine.rules.LockLevel} and
 * {@link io.github.hanumanthraghavendra.schemaguard.engine.rules.TypeChange}; all of them are
 * checked against measurements taken on a real PostgreSQL server.
 */
package io.github.hanumanthraghavendra.schemaguard.engine.rules;
