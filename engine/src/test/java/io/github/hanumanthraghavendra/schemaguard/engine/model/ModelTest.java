package io.github.hanumanthraghavendra.schemaguard.engine.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModelTest {

    @ParameterizedTest
    @ValueSource(strings = {"abc", "a1_b", "_x", "orders"})
    void plainLowerCaseNamesNeedNoQuotes(String name) {
        assertThat(Identifiers.needsQuoting(name)).isFalse();
        assertThat(Identifiers.quoteIfNeeded(name)).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1abc", "Abc", "a b", "a$b", "a-b", "select", "user", "table", "größe"})
    void otherNamesAreQuoted(String name) {
        assertThat(Identifiers.needsQuoting(name)).isTrue();
        assertThat(Identifiers.quoteIfNeeded(name)).isEqualTo("\"" + name + "\"");
    }

    @Test
    void quotingDoublesEmbeddedQuotes() {
        assertThat(Identifiers.quote("a\"b")).isEqualTo("\"a\"\"b\"");
    }

    @Test
    void qualifiedNamePrintsAsSql() {
        assertThat(new QualifiedName("public", "orders").toSql()).isEqualTo("public.orders");
        assertThat(new QualifiedName("My Schema", "Order")).hasToString("\"My Schema\".\"Order\"");
        assertThatThrownBy(() -> new QualifiedName(null, "t")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QualifiedName("s", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void dataTypeValidatesAndCopiesItsInputs() {
        List<String> modifiers = new ArrayList<>(List.of("12", "2"));
        DataType type = new DataType(null, "numeric", modifiers, 1);
        modifiers.clear();
        assertThat(type.toSql()).isEqualTo("numeric(12,2)[]");
        assertThat(DataType.of("text").toSql()).isEqualTo("text");
        assertThatThrownBy(() -> type.modifiers().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new DataType(null, "text", List.of(), -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DataType(null, null, List.of(), 0)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void typeAliasesResolveToCanonicalNames() {
        assertThat(TypeNames.canonical("int4")).isEqualTo("integer");
        assertThat(TypeNames.canonical("citext")).isEqualTo("citext");
        assertThat(TypeNames.isAlias("varchar")).isTrue();
        assertThat(TypeNames.isAlias("integer")).isFalse();
        assertThat(TypeNames.isMultiWordBuiltIn("double precision")).isTrue();
        assertThat(TypeNames.isMultiWordBuiltIn("interval day to second")).isTrue();
        assertThat(TypeNames.isMultiWordBuiltIn("Odd Type")).isFalse();
    }

    @Test
    void expressionMustHaveContent() {
        assertThat(new Expression("a > 0")).hasToString("a > 0");
        assertThatThrownBy(() -> new Expression("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Expression(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void columnCanBeCopiedWithADifferentNullability() {
        Column column =
                new Column("a", DataType.of("integer"), false, Optional.empty(), Identity.NONE, Optional.empty(), Optional.empty());
        assertThat(column.withNotNull(true).notNull()).isTrue();
        assertThat(column.withNotNull(true).name()).isEqualTo("a");
        assertThat(column.notNull()).isFalse();
        assertThatThrownBy(
                        () -> new Column("a", null, false, Optional.empty(), Identity.NONE, Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constraintsAreValueObjects() {
        PrimaryKey first = new PrimaryKey(Optional.of("pk"), List.of("id"), List.of(), Deferrability.NOT_DEFERRABLE);
        PrimaryKey second = new PrimaryKey(Optional.of("pk"), List.of("id"), List.of(), Deferrability.NOT_DEFERRABLE);
        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first.withDeferrability(Deferrability.DEFERRABLE_INITIALLY_DEFERRED)).isNotEqualTo(first);
        assertThatThrownBy(() -> new Check(Optional.empty(), null, false, false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GeneratedColumn(null, true)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void tableFindsColumnsAndItsPrimaryKey() {
        Column id =
                new Column("id", DataType.of("bigint"), true, Optional.empty(), Identity.NONE, Optional.empty(), Optional.empty());
        PrimaryKey key = new PrimaryKey(Optional.empty(), List.of("id"), List.of(), Deferrability.NOT_DEFERRABLE);
        Table table =
                new Table(
                        new QualifiedName("public", "t"),
                        List.of(id),
                        List.of(key),
                        Persistence.PERMANENT,
                        List.of(),
                        Optional.empty());
        assertThat(table.column("id")).contains(id);
        assertThat(table.column("ID")).isEmpty();
        assertThat(table.primaryKey()).contains(key);
        assertThatThrownBy(() -> table.columns().add(id)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void indexKeyIsEitherAColumnOrAnExpression() {
        IndexColumn column = IndexColumn.ofColumn("a");
        assertThat(column.order()).isEqualTo(SortOrder.ASC);
        assertThat(column.nulls()).isEqualTo(NullsOrder.LAST);
        assertThatThrownBy(
                        () ->
                                new IndexColumn(
                                        Optional.of("a"),
                                        Optional.of(new Expression("a + 1")),
                                        Optional.empty(),
                                        Optional.empty(),
                                        SortOrder.ASC,
                                        NullsOrder.LAST))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new IndexColumn(
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        SortOrder.ASC,
                                        NullsOrder.LAST))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sequenceRejectsAZeroIncrementAndCanChangeOwner() {
        QualifiedName name = new QualifiedName("public", "s");
        Sequence sequence =
                new Sequence(
                        name, Persistence.PERMANENT, Optional.empty(), Optional.empty(), 1,
                        Optional.empty(), Optional.empty(), 1, false, Optional.empty());
        ColumnRef owner = new ColumnRef(new QualifiedName("public", "t"), "id");
        assertThat(sequence.withOwnedBy(Optional.of(owner)).ownedBy()).contains(owner);
        assertThat(sequence.ownedBy()).isEmpty();
        assertThatThrownBy(
                        () ->
                                new Sequence(
                                        name, Persistence.PERMANENT, Optional.empty(), Optional.empty(), 0,
                                        Optional.empty(), Optional.empty(), 1, false, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ColumnRef(null, "id")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void enumAndIndexCopyTheirLists() {
        List<String> labels = new ArrayList<>(List.of("a", "b"));
        EnumType type = new EnumType(new QualifiedName("public", "mood"), labels);
        labels.clear();
        assertThat(type.labels()).containsExactly("a", "b");

        Index index =
                new Index(
                        Optional.of("i"), new QualifiedName("public", "t"), false, "btree",
                        List.of(IndexColumn.ofColumn("a")), List.of(), false, Optional.empty());
        assertThatThrownBy(() -> index.columns().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> index.include().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void objectKindsKnowTheirKeyword() {
        assertThat(ObjectKind.TABLE.keyword()).isEqualTo("table");
        assertThat(ObjectKind.SEQUENCE.keyword()).isEqualTo("sequence");
    }
}
