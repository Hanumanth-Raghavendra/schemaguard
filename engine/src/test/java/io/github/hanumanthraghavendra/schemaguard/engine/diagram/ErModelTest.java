package io.github.hanumanthraghavendra.schemaguard.engine.diagram;

import static io.github.hanumanthraghavendra.schemaguard.engine.diagram.ErModel.Status.ADDED;
import static io.github.hanumanthraghavendra.schemaguard.engine.diagram.ErModel.Status.CHANGED;
import static io.github.hanumanthraghavendra.schemaguard.engine.diagram.ErModel.Status.REMOVED;
import static io.github.hanumanthraghavendra.schemaguard.engine.diagram.ErModel.Status.UNCHANGED;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogBuilder;
import io.github.hanumanthraghavendra.schemaguard.engine.catalog.CatalogResult;
import io.github.hanumanthraghavendra.schemaguard.engine.diagram.ErModel.Attribute;
import io.github.hanumanthraghavendra.schemaguard.engine.diagram.ErModel.Entity;
import io.github.hanumanthraghavendra.schemaguard.engine.diagram.ErModel.Relationship;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ErModelTest {

    private static ErModel model(String before, String after) {
        CatalogResult old = CatalogBuilder.fromSql(before);
        CatalogResult current = CatalogBuilder.fromSql(after);
        assertThat(old.hasErrors()).as("before: %s", old.diagnostics()).isFalse();
        assertThat(current.hasErrors()).as("after: %s", current.diagnostics()).isFalse();
        return ErModel.of(SchemaDiff.compare(old.catalog(), current.catalog()));
    }

    private static Entity table(ErModel model, String name) {
        return model.tables().stream()
                .filter(t -> t.name().equals(new QualifiedName("public", name)))
                .findFirst()
                .orElseThrow();
    }

    private static Attribute column(Entity table, String name) {
        return table.columns().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void marksAddedRemovedAndChangedColumnsOfATableInBothSchemas() {
        ErModel model = model(
                """
                CREATE TABLE customers (id integer PRIMARY KEY, email text NOT NULL, name text);
                CREATE TABLE orders (id integer PRIMARY KEY, customer_id integer NOT NULL, total numeric(10, 2));
                """,
                """
                CREATE TABLE customers (id integer PRIMARY KEY, email text NOT NULL, full_name text,
                  created_at timestamptz NOT NULL DEFAULT now());
                CREATE TABLE orders (id bigint PRIMARY KEY, customer_id integer NOT NULL REFERENCES customers (id),
                  total numeric(10, 2));
                """);

        Entity customers = table(model, "customers");
        assertThat(customers.status()).isEqualTo(CHANGED);
        assertThat(customers.columns()).extracting(Attribute::name, Attribute::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("id", UNCHANGED),
                        org.assertj.core.groups.Tuple.tuple("email", UNCHANGED),
                        org.assertj.core.groups.Tuple.tuple("full_name", ADDED),
                        org.assertj.core.groups.Tuple.tuple("created_at", ADDED),
                        org.assertj.core.groups.Tuple.tuple("name", REMOVED));
        assertThat(column(customers, "id").primaryKey()).isTrue();
        assertThat(column(customers, "email").notNull()).isTrue();
        assertThat(column(customers, "created_at").type()).isEqualTo("timestamp with time zone");

        Entity orders = table(model, "orders");
        assertThat(orders.status()).isEqualTo(CHANGED);
        Attribute id = column(orders, "id");
        assertThat(id.status()).isEqualTo(CHANGED);
        assertThat(id.type()).isEqualTo("bigint");
        assertThat(id.previousType()).contains("integer");
        assertThat(column(orders, "total").status()).isEqualTo(UNCHANGED);
        assertThat(column(orders, "total").previousType()).isEmpty();

        assertThat(model.relationships()).singleElement().satisfies(fk -> {
            assertThat(fk.from()).isEqualTo(new QualifiedName("public", "orders"));
            assertThat(fk.columns()).containsExactly("customer_id");
            assertThat(fk.to()).isEqualTo(new QualifiedName("public", "customers"));
            assertThat(fk.referencedColumns()).containsExactly("id");
            assertThat(fk.status()).isEqualTo(ADDED);
        });
    }

    @Test
    void wholeTablesAndTheirForeignKeysAreAddedOrRemoved() {
        ErModel model = model(
                """
                CREATE TABLE teams (id int PRIMARY KEY);
                CREATE TABLE legacy (id int PRIMARY KEY, team_id int REFERENCES teams);
                """,
                """
                CREATE TABLE teams (id int PRIMARY KEY);
                CREATE TABLE members (id int PRIMARY KEY, team_id int NOT NULL REFERENCES teams);
                """);

        assertThat(model.tables()).extracting(Entity::name, Entity::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(new QualifiedName("public", "legacy"), REMOVED),
                        org.assertj.core.groups.Tuple.tuple(new QualifiedName("public", "members"), ADDED),
                        org.assertj.core.groups.Tuple.tuple(new QualifiedName("public", "teams"), UNCHANGED));
        assertThat(table(model, "members").columns()).allMatch(c -> c.status() == ADDED);
        assertThat(table(model, "legacy").columns()).allMatch(c -> c.status() == REMOVED);
        assertThat(model.relationships()).extracting(r -> r.from().name(), Relationship::status)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("legacy", REMOVED),
                        org.assertj.core.groups.Tuple.tuple("members", ADDED));
        assertThat(model.relationships()).allSatisfy(r -> assertThat(r.referencedColumns()).containsExactly("id"));
    }

    @Test
    void droppedAndChangedForeignKeysOfAKeptTable() {
        ErModel model = model(
                """
                CREATE TABLE a (id int PRIMARY KEY);
                CREATE TABLE b (id int PRIMARY KEY);
                CREATE TABLE c (id int PRIMARY KEY,
                  a_id int CONSTRAINT c_a_fk REFERENCES a,
                  b_id int CONSTRAINT c_b_fk REFERENCES b);
                """,
                """
                CREATE TABLE a (id int PRIMARY KEY);
                CREATE TABLE b (id int PRIMARY KEY);
                CREATE TABLE c (id int PRIMARY KEY,
                  a_id int,
                  b_id int CONSTRAINT c_b_fk REFERENCES b ON DELETE CASCADE);
                """);

        assertThat(model.relationships()).extracting(Relationship::name, Relationship::status)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(Optional.of("c_b_fk"), CHANGED),
                        org.assertj.core.groups.Tuple.tuple(Optional.of("c_a_fk"), REMOVED));
        Entity c = table(model, "c");
        assertThat(c.status()).isEqualTo(CHANGED);
        assertThat(c.columns()).allMatch(col -> col.status() == UNCHANGED);
    }

    @Test
    void respelledTypesAndUntouchedTablesAreUnchanged() {
        ErModel model = model(
                "CREATE TABLE t (id int4 PRIMARY KEY, note varchar(20) NOT NULL);",
                "CREATE TABLE t (id integer PRIMARY KEY, note character varying(20) NOT NULL);");

        Entity t = table(model, "t");
        assertThat(t.status()).isEqualTo(UNCHANGED);
        assertThat(t.columns()).allMatch(col -> col.status() == UNCHANGED);
        assertThat(model.relationships()).isEmpty();
    }

    @Test
    void aColumnThatJoinsThePrimaryKeyIsChanged() {
        ErModel model = model(
                "CREATE TABLE t (a int NOT NULL, b int NOT NULL, PRIMARY KEY (a));",
                "CREATE TABLE t (a int NOT NULL, b int NOT NULL, PRIMARY KEY (a, b));");

        Entity t = table(model, "t");
        assertThat(column(t, "a").status()).isEqualTo(UNCHANGED);
        assertThat(column(t, "b").status()).isEqualTo(CHANGED);
        assertThat(column(t, "b").primaryKey()).isTrue();
        assertThat(t.status()).isEqualTo(CHANGED);
    }
}
