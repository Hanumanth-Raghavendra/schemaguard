package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import io.github.hanumanthraghavendra.schemaguard.engine.rules.compatibility.ConstraintDropped;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.compatibility.DefaultDroppedOnRequiredColumn;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.compatibility.EnumValueAdded;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.compatibility.IdentityAddedToExistingColumn;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.compatibility.IdentityMadeAlways;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.compatibility.IdentityRemoved;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.compatibility.InheritanceChanged;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.compatibility.NotNullDropped;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.compatibility.SequenceDropped;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.dataloss.ColumnDropped;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.dataloss.ColumnTypeNarrowed;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.dataloss.EnumValueRemoved;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.dataloss.PossibleEnumValueRename;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.dataloss.PossibleRename;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.dataloss.TableDropped;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.failure.ColumnTypeConversion;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.failure.NotNullColumnWithoutDefault;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.locking.CheckAddedValidated;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.locking.CollationChangedOnIndexedColumn;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.locking.ConstraintReplaced;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.locking.ForeignKeyAddedValidated;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.locking.IndexAddedWithoutConcurrently;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.locking.IndexDroppedWithoutConcurrently;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.locking.IndexReplaced;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.locking.LockTimeout;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.locking.NotNullSetWithScan;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.locking.PrimaryKeyAdded;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.locking.UniqueAdded;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.rewrite.ColumnAddedWithUnknownDefault;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.rewrite.ColumnAddedWithVolatileDefault;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.rewrite.ColumnOrderChanged;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.rewrite.ColumnTypeRewrite;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.rewrite.EnumValuesReordered;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.rewrite.GeneratedColumnAdded;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.rewrite.GeneratedExpressionChanged;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.rewrite.IdentityColumnAdded;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.rewrite.PartitioningChanged;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.rewrite.PersistenceChanged;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The set of rules a report is made with.
 *
 * <p>{@link #standard()} holds every rule SchemaGuard ships. {@link #without(Set)} gives a
 * copy with some rules switched off, for teams that accept a risk on purpose.
 */
public final class RuleRegistry {

    private static final RuleRegistry STANDARD =
            new RuleRegistry(
                    List.of(
                            new IndexAddedWithoutConcurrently(),
                            new IndexDroppedWithoutConcurrently(),
                            new IndexReplaced(),
                            new ForeignKeyAddedValidated(),
                            new CheckAddedValidated(),
                            new NotNullSetWithScan(),
                            new PrimaryKeyAdded(),
                            new UniqueAdded(),
                            new ConstraintReplaced(),
                            new CollationChangedOnIndexedColumn(),
                            new LockTimeout(),
                            new ColumnTypeRewrite(),
                            new ColumnAddedWithVolatileDefault(),
                            new ColumnAddedWithUnknownDefault(),
                            new IdentityColumnAdded(),
                            new GeneratedColumnAdded(),
                            new GeneratedExpressionChanged(),
                            new PersistenceChanged(),
                            new PartitioningChanged(),
                            new EnumValuesReordered(),
                            new ColumnOrderChanged(),
                            new TableDropped(),
                            new ColumnDropped(),
                            new EnumValueRemoved(),
                            new ColumnTypeNarrowed(),
                            new PossibleRename(),
                            new PossibleEnumValueRename(),
                            new NotNullColumnWithoutDefault(),
                            new ColumnTypeConversion(),
                            new DefaultDroppedOnRequiredColumn(),
                            new NotNullDropped(),
                            new ConstraintDropped(),
                            new SequenceDropped(),
                            new IdentityAddedToExistingColumn(),
                            new IdentityRemoved(),
                            new IdentityMadeAlways(),
                            new EnumValueAdded(),
                            new InheritanceChanged()));

    private final List<Rule> rules;

    private RuleRegistry(List<Rule> rules) {
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Rule rule : rules) {
            if (!ids.add(rule.info().id()) || !names.add(rule.info().name())) {
                throw new IllegalArgumentException("Two rules share the id or name of " + rule);
            }
        }
        this.rules = rules.stream().sorted(Comparator.comparing((Rule rule) -> rule.info().id())).toList();
    }

    /** Every rule SchemaGuard ships, ordered by id. */
    public static RuleRegistry standard() {
        return STANDARD;
    }

    /** A registry of the given rules. Ids and names must be unique. */
    public static RuleRegistry of(List<Rule> rules) {
        return new RuleRegistry(rules);
    }

    public List<Rule> rules() {
        return rules;
    }

    /** Looks a rule up by its id, such as {@code SG-R101}, or by its name. */
    public Optional<Rule> rule(String idOrName) {
        return rules.stream()
                .filter(rule -> rule.info().id().equals(idOrName) || rule.info().name().equals(idOrName))
                .findFirst();
    }

    /**
     * A copy without the rules whose id or name is given.
     *
     * @throws IllegalArgumentException if an entry matches no rule, so a typing mistake in a
     *     configuration file cannot leave a rule switched on unnoticed
     */
    public RuleRegistry without(Set<String> idsOrNames) {
        for (String entry : idsOrNames) {
            if (rule(entry).isEmpty()) {
                throw new IllegalArgumentException("Unknown rule: " + entry);
            }
        }
        return new RuleRegistry(
                rules.stream()
                        .filter(rule -> !idsOrNames.contains(rule.info().id()) && !idsOrNames.contains(rule.info().name()))
                        .toList());
    }
}
