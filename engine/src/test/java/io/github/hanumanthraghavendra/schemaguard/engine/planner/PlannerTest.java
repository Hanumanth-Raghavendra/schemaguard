package io.github.hanumanthraghavendra.schemaguard.engine.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.hanumanthraghavendra.schemaguard.engine.diff.Change;
import io.github.hanumanthraghavendra.schemaguard.engine.diff.SchemaDiff;
import io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog;
import io.github.hanumanthraghavendra.schemaguard.engine.model.QualifiedName;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.Operation;
import io.github.hanumanthraghavendra.schemaguard.engine.rules.RulesTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What the planner writes, statement by statement. Whether the statements work is the
 * business of {@link PlanOracleTest}.
 */
class PlannerTest {

    private static final String T = "CREATE TABLE t (a int, b int);";
    private static final QualifiedName TABLE = new QualifiedName("public", "t");

    private static MigrationPlan direct(String before, String after) {
        return Planner.plan(RulesTest.catalog(before), RulesTest.catalog(after));
    }

    private static MigrationPlan safe(String before, String after) {
        return Planner.plan(RulesTest.catalog(before), RulesTest.catalog(after), PlanOptions.safe());
    }

    private static List<String> sql(MigrationPlan plan) {
        return plan.steps().stream().map(Step::sql).toList();
    }

    // ---------------------------------------------------------------- the two routes

    @Test
    void equalSchemasNeedNoStatementOnEitherRoute() {
        assertThat(direct(T, T).steps()).isEmpty();
        assertThat(safe(T, T).steps()).isEmpty();
        assertThat(safe(T, T).isComplete()).isTrue();
        assertThat(safe(T, T).toSql()).isEqualTo("-- SchemaGuard migration plan, safe route: 0 statements\n");
    }

    @Test
    void anIndexIsBuiltConcurrentlyOnlyWhereTheTableCanHaveRows() {
        String index = "CREATE INDEX t_a_idx ON t (a);";
        assertThat(sql(direct(T, T + index))).containsExactly("CREATE INDEX t_a_idx ON public.t USING btree (a)");
        assertThat(sql(safe(T, T + index)))
                .containsExactly("SET lock_timeout = '5s'", "CREATE INDEX CONCURRENTLY t_a_idx ON public.t USING btree (a)");
        assertThat(sql(safe("", T + index)))
                .containsExactly(
                        "SET lock_timeout = '5s'",
                        "CREATE TABLE public.t (\n    a integer,\n    b integer\n)",
                        "CREATE INDEX t_a_idx ON public.t USING btree (a)");
    }

    @Test
    void aConcurrentIndexBuildIsMarkedAsNotTransactional() {
        MigrationPlan plan = safe(T, T + "CREATE UNIQUE INDEX t_a_idx ON t (a);");
        Step build = plan.steps().get(1);
        assertThat(build.transactional()).isFalse();
        assertThat(build.operation()).contains(Operation.CREATE_INDEX_CONCURRENTLY);
        assertThat(build.table()).contains(TABLE);
        assertThat(build.change()).containsInstanceOf(Change.IndexAdded.class);
        assertThat(plan.steps().get(0).transactional()).isTrue();
        assertThat(plan.steps().get(0).change()).isEmpty();
    }

    @Test
    void aForeignKeyIsAddedUnvalidatedAndValidatedLast() {
        String p = "CREATE TABLE p (id int PRIMARY KEY);";
        MigrationPlan plan = safe(p + T, p + T + "ALTER TABLE t ADD CONSTRAINT t_fk FOREIGN KEY (a) REFERENCES p (id); CREATE INDEX t_a_idx ON t (a);");
        assertThat(sql(plan))
                .containsExactly(
                        "SET lock_timeout = '5s'",
                        "ALTER TABLE public.t ADD CONSTRAINT t_fk FOREIGN KEY (a) REFERENCES public.p (id) NOT VALID",
                        "CREATE INDEX CONCURRENTLY t_a_idx ON public.t USING btree (a)",
                        "ALTER TABLE public.t VALIDATE CONSTRAINT t_fk");
        assertThat(plan.steps().get(0).operation()).isEmpty();
        assertThat(plan.steps().subList(1, 4)).extracting(step -> step.operation().orElseThrow())
                .containsExactly(Operation.ADD_FOREIGN_KEY_NOT_VALID, Operation.CREATE_INDEX_CONCURRENTLY, Operation.VALIDATE_FOREIGN_KEY);
    }

    @Test
    void notNullIsReachedThroughAValidatedCheck() {
        assertThat(sql(direct(T, "CREATE TABLE t (a int NOT NULL, b int);")))
                .containsExactly("ALTER TABLE public.t ALTER COLUMN a SET NOT NULL");
        MigrationPlan plan = safe(T, "CREATE TABLE t (a int NOT NULL, b int);");
        assertThat(sql(plan))
                .containsExactly(
                        "SET lock_timeout = '5s'",
                        "ALTER TABLE public.t ADD CONSTRAINT t_a_not_null CHECK (a IS NOT NULL) NOT VALID",
                        "ALTER TABLE public.t VALIDATE CONSTRAINT t_a_not_null",
                        "ALTER TABLE public.t ALTER COLUMN a SET NOT NULL",
                        "ALTER TABLE public.t DROP CONSTRAINT t_a_not_null");
        assertThat(plan.steps().get(3).operation()).contains(Operation.SET_NOT_NULL_WITH_VALID_CHECK);
        assertThat(plan.needsDecisions()).isFalse();
    }

    @Test
    void notNullNeedsNoProofWhenTheOldSchemaHasOne() {
        String proven = "CREATE TABLE t (a int%s, b int, CONSTRAINT a_present CHECK (a IS NOT NULL));";
        assertThat(sql(safe(String.format(proven, ""), String.format(proven, " NOT NULL"))))
                .containsExactly("SET lock_timeout = '5s'", "ALTER TABLE public.t ALTER COLUMN a SET NOT NULL");
    }

    @Test
    void theNameOfTheProofAvoidsNamesThatAreTaken() {
        MigrationPlan plan =
                safe(
                        "CREATE TABLE t (a int, CONSTRAINT t_a_not_null CHECK (a > 0));",
                        "CREATE TABLE t (a int NOT NULL, CONSTRAINT t_a_not_null CHECK (a > 0));");
        assertThat(sql(plan).get(1)).isEqualTo("ALTER TABLE public.t ADD CONSTRAINT t_a_not_null2 CHECK (a IS NOT NULL) NOT VALID");
    }

    @Test
    void aKeyIsBuiltAsAConcurrentIndexAndThenTakenOver() {
        MigrationPlan plan = safe(T, T + "ALTER TABLE t ADD CONSTRAINT t_a_key UNIQUE NULLS NOT DISTINCT (a) INCLUDE (b) DEFERRABLE;");
        assertThat(sql(plan))
                .containsExactly(
                        "SET lock_timeout = '5s'",
                        "CREATE UNIQUE INDEX CONCURRENTLY t_a_key ON public.t USING btree (a) INCLUDE (b) NULLS NOT DISTINCT",
                        "ALTER TABLE public.t ADD CONSTRAINT t_a_key UNIQUE USING INDEX t_a_key DEFERRABLE");
        assertThat(plan.steps().get(2).operation()).contains(Operation.ADD_CONSTRAINT_USING_INDEX);
    }

    @Test
    void aChangedCheckIsAddedNextToTheOldOneAndSwappedIn() {
        String before = "CREATE TABLE t (a int, CONSTRAINT limits CHECK (a > 0));";
        String after = "CREATE TABLE t (a int, CONSTRAINT limits CHECK (a > 1));";
        assertThat(sql(direct(before, after)))
                .containsExactly(
                        "ALTER TABLE public.t DROP CONSTRAINT limits",
                        "ALTER TABLE public.t ADD CONSTRAINT limits CHECK (a > 1)");
        assertThat(sql(safe(before, after)))
                .containsExactly(
                        "SET lock_timeout = '5s'",
                        "ALTER TABLE public.t ADD CONSTRAINT limits_new CHECK (a > 1) NOT VALID",
                        "ALTER TABLE public.t VALIDATE CONSTRAINT limits_new",
                        "ALTER TABLE public.t DROP CONSTRAINT limits",
                        "ALTER TABLE public.t RENAME CONSTRAINT limits_new TO limits");
    }

    @Test
    void aChangedKeyIsSwappedInOneStatementSoTheTableIsNeverWithoutIt() {
        MigrationPlan plan =
                safe(
                        "CREATE TABLE t (a int, b int, CONSTRAINT one UNIQUE (a));",
                        "CREATE TABLE t (a int, b int, CONSTRAINT one UNIQUE (a, b));");
        assertThat(sql(plan))
                .containsExactly(
                        "SET lock_timeout = '5s'",
                        "CREATE UNIQUE INDEX CONCURRENTLY one_new ON public.t USING btree (a, b)",
                        "ALTER TABLE public.t DROP CONSTRAINT one, ADD CONSTRAINT one UNIQUE USING INDEX one_new");
    }

    @Test
    void aChangedIndexIsBuiltUnderATemporaryNameAndSwappedIn() {
        MigrationPlan plan = safe(T + "CREATE INDEX t_idx ON t (a);", T + "CREATE INDEX t_idx ON t (a, b);");
        assertThat(sql(plan))
                .containsExactly(
                        "SET lock_timeout = '5s'",
                        "CREATE INDEX CONCURRENTLY t_idx_new ON public.t USING btree (a, b)",
                        "DROP INDEX CONCURRENTLY public.t_idx",
                        "ALTER INDEX public.t_idx_new RENAME TO t_idx");
    }

    @Test
    void temporaryNamesStayWithinTheLimitOfPostgres() {
        String name = "c".repeat(63);
        MigrationPlan plan =
                safe(
                        "CREATE TABLE t (a int, CONSTRAINT " + name + " CHECK (a > 0));",
                        "CREATE TABLE t (a int, CONSTRAINT " + name + " CHECK (a > 1));");
        assertThat(sql(plan).get(1)).contains("ADD CONSTRAINT " + "c".repeat(56) + "_new CHECK");
    }

    // ---------------------------------------------------------------- order

    @Test
    void statementsRunInAnOrderThatSatisfiesEveryDependency() {
        MigrationPlan plan =
                direct(
                        "CREATE TABLE p (id int PRIMARY KEY); CREATE TABLE t (id serial, p_id int REFERENCES p (id), old int);"
                                + " CREATE INDEX t_old_idx ON t (old);",
                        "CREATE TYPE mood AS ENUM ('ok'); CREATE TABLE t (id int GENERATED ALWAYS AS IDENTITY, p_id int, m mood);"
                                + " CREATE TABLE n (t_id int); CREATE INDEX n_idx ON n (t_id);");
        assertThat(sql(plan))
                .containsExactly(
                        "CREATE TYPE public.mood AS ENUM ('ok')",
                        "ALTER TABLE public.t DROP CONSTRAINT t_p_id_fkey",
                        "DROP TABLE public.p",
                        "DROP INDEX public.t_old_idx",
                        "CREATE TABLE public.n (\n    t_id integer\n)",
                        "ALTER TABLE public.t ADD COLUMN m public.mood",
                        "ALTER TABLE public.t ALTER COLUMN id DROP DEFAULT",
                        "ALTER TABLE public.t ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY",
                        "ALTER TABLE public.t DROP COLUMN old",
                        "CREATE INDEX n_idx ON public.n USING btree (t_id)",
                        "DROP SEQUENCE public.t_id_seq");
    }

    @Test
    void enumValuesAreAddedBackToFrontSoEachNeighbourExists() {
        MigrationPlan plan = direct("CREATE TYPE mood AS ENUM ('b', 'e');", "CREATE TYPE mood AS ENUM ('a', 'b', 'c', 'd', 'e', 'f');");
        assertThat(sql(plan))
                .containsExactly(
                        "ALTER TYPE public.mood ADD VALUE 'f'",
                        "ALTER TYPE public.mood ADD VALUE 'd' BEFORE 'e'",
                        "ALTER TYPE public.mood ADD VALUE 'c' BEFORE 'd'",
                        "ALTER TYPE public.mood ADD VALUE 'a' BEFORE 'b'");
        assertThat(plan.steps()).allSatisfy(step -> assertThat(step.transactional()).isFalse());
    }

    @Test
    void tablesThatReferToEachOtherAreDroppedInOneStatement() {
        MigrationPlan plan =
                direct(
                        "CREATE TABLE a (id int PRIMARY KEY, b_id int); CREATE TABLE b (id int PRIMARY KEY, a_id int REFERENCES a (id));"
                                + " ALTER TABLE a ADD CONSTRAINT a_b_fk FOREIGN KEY (b_id) REFERENCES b (id); CREATE TABLE c (a_id int REFERENCES a (id));",
                        "");
        assertThat(sql(plan)).containsExactly("DROP TABLE public.c", "DROP TABLE public.a, public.b");
    }

    @Test
    void aTypeAndACollationChangeBecomeOneStatement() {
        MigrationPlan plan = direct("CREATE TABLE t (a varchar(10), b text);", "CREATE TABLE t (a text COLLATE \"C\", b int);");
        assertThat(sql(plan))
                .containsExactly(
                        "ALTER TABLE public.t ALTER COLUMN a TYPE text COLLATE \"C\"",
                        "ALTER TABLE public.t ALTER COLUMN b TYPE integer USING b::integer");
        assertThat(plan.steps()).extracting(step -> step.operation().orElseThrow())
                .containsExactly(Operation.ALTER_TYPE_NO_REWRITE, Operation.ALTER_TYPE_REWRITE);
    }

    @Test
    void aSequenceWithNewLimitsWarnsThatItsPositionMayNotFit() {
        MigrationPlan plan = direct("CREATE SEQUENCE s CYCLE; CREATE SEQUENCE d;", "CREATE SEQUENCE s; CREATE SEQUENCE d INCREMENT BY -1;");
        assertThat(sql(plan))
                .containsExactly(
                        "ALTER SEQUENCE public.d AS bigint START WITH -1 INCREMENT BY -1 MINVALUE -9223372036854775808 MAXVALUE -1 CACHE 1 NO CYCLE",
                        "ALTER SEQUENCE public.s AS bigint START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 CACHE 1 NO CYCLE");
        assertThat(plan.steps().get(0).note()).startsWith("Fails if the sequence now stands outside the new limits");
        assertThat(plan.steps().get(1).note()).isEmpty();
    }

    @Test
    void inheritedTablesAreCreatedAfterAndDroppedBeforeTheirParents() {
        String family = "CREATE TABLE zebra (id int); CREATE TABLE child (legs int) INHERITS (zebra); CREATE TABLE a_grandchild () INHERITS (child);";
        assertThat(sql(direct("", family)))
                .extracting(statement -> statement.substring(0, statement.indexOf(" (")))
                .containsExactly("CREATE TABLE public.zebra", "CREATE TABLE public.child", "CREATE TABLE public.a_grandchild");
        assertThat(sql(direct(family, "")))
                .containsExactly("DROP TABLE public.a_grandchild", "DROP TABLE public.child", "DROP TABLE public.zebra");
    }

    // ---------------------------------------------------------------- what the plan cannot do

    @Test
    void changesWithoutAStatementAreLeftOutAndSaidSo() {
        MigrationPlan plan =
                direct(
                        "CREATE TYPE mood AS ENUM ('sad', 'ok'); CREATE TABLE t (a int, c int) PARTITION BY RANGE (a); CREATE TEMPORARY TABLE x (a int);",
                        "CREATE TYPE mood AS ENUM ('ok'); CREATE TABLE t (a int, c int GENERATED ALWAYS AS (a * 2) STORED); CREATE TABLE x (a int);");
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.isComplete()).isFalse();
        assertThat(plan.unplanned())
                .extracting(item -> item.change().getClass().getSimpleName())
                .containsExactlyInAnyOrder("EnumValueRemoved", "TablePartitioningChanged", "ColumnGeneratedChanged", "TablePersistenceChanged");
        assertThat(plan.toSql())
                .startsWith("-- SchemaGuard migration plan, direct route: 0 statements\n\n-- NOT PLANNED: the following changes need manual work.\n")
                .contains("--   ~ enum public.mood: value 'sad' removed\n--     PostgreSQL cannot remove a value from an enum.");
        assertThat(direct("CREATE TYPE mood AS ENUM ('sad', 'ok');", "CREATE TYPE mood AS ENUM ('ok', 'sad');").unplanned()).hasSize(1);
    }

    @Test
    void columnOrderIsIgnoredWithoutMakingThePlanIncomplete() {
        MigrationPlan plan = direct(T, "CREATE TABLE t (b int, a int);");
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.isComplete()).isTrue();
    }

    @Test
    void risksTheSafeRouteCannotRemoveAreMarkedOnTheStepThatCarriesThem() {
        MigrationPlan plan = safe("CREATE TABLE t (a text DEFAULT '7', b int);", "CREATE TABLE t (a int DEFAULT 7);");
        assertThat(plan.needsDecisions()).isTrue();
        assertThat(plan.steps())
                .filteredOn(step -> !step.cautions().isEmpty())
                .extracting(Step::sql, Step::cautions)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "ALTER TABLE public.t ALTER COLUMN a TYPE integer USING a::integer",
                                List.of("SG-R201 Column type change rewrites the table", "SG-R402 Column changes to a different kind of type")),
                        org.assertj.core.groups.Tuple.tuple(
                                "ALTER TABLE public.t DROP COLUMN b", List.of("SG-R302 Column is dropped with all its values")));
        assertThat(plan.toSql()).contains("-- CAUTION: SG-R302 Column is dropped with all its values\nALTER TABLE public.t DROP COLUMN b;\n");
        assertThat(direct("CREATE TABLE t (a int, b int);", "CREATE TABLE t (a int);").needsDecisions()).isFalse();
    }

    // ---------------------------------------------------------------- confirmed renames

    @Test
    void aConfirmedRenameReplacesTheDropAndTheAdd() {
        Catalog before = RulesTest.catalog("CREATE TABLE t (id int, display_name text);");
        Catalog after = RulesTest.catalog("CREATE TABLE t (id int NOT NULL, full_name text);");
        assertThat(sql(Planner.plan(before, after)))
                .containsExactly(
                        "ALTER TABLE public.t ADD COLUMN full_name text",
                        "ALTER TABLE public.t ALTER COLUMN id SET NOT NULL",
                        "ALTER TABLE public.t DROP COLUMN display_name");

        List<Rename> renames = SchemaDiff.compare(before, after).changes().stream().flatMap(c -> Rename.of(c).stream()).toList();
        assertThat(renames).containsExactly(new Rename.OfColumn(TABLE, "display_name", "full_name"));
        MigrationPlan plan = Planner.plan(before, after, PlanOptions.direct().withRenames(renames));
        assertThat(sql(plan))
                .containsExactly(
                        "ALTER TABLE public.t RENAME COLUMN display_name TO full_name",
                        "ALTER TABLE public.t ALTER COLUMN id SET NOT NULL");
        assertThat(plan.steps().get(0).note()).isEqualTo("Confirmed rename: the data is kept");
        assertThat(plan.steps().get(0).operation()).contains(Operation.RENAME_COLUMN);
        assertThat(plan.diff().changes()).hasSize(1);
    }

    @Test
    void tablesCanBeRenamedAndMovedAndEnumValuesRenamed() {
        Catalog before = RulesTest.catalog("CREATE TYPE mood AS ENUM ('sad'); CREATE TABLE logs (id int);");
        Catalog after = RulesTest.catalog("CREATE TYPE mood AS ENUM ('unhappy'); CREATE TABLE audit.events (id int);");
        MigrationPlan plan =
                Planner.plan(
                        before,
                        after,
                        PlanOptions.safe()
                                .withRenames(
                                        Set.of(new Rename.OfTable(new QualifiedName("public", "logs"), new QualifiedName("audit", "events"))))
                                .withRenames(
                                        List.of(
                                                new Rename.OfTable(new QualifiedName("public", "logs"), new QualifiedName("audit", "events")),
                                                new Rename.OfEnumValue(new QualifiedName("public", "mood"), "sad", "unhappy"))));
        assertThat(sql(plan))
                .containsExactly(
                        "SET lock_timeout = '5s'",
                        "ALTER TABLE public.logs RENAME TO events",
                        "ALTER TABLE public.events SET SCHEMA audit",
                        "ALTER TYPE public.mood RENAME VALUE 'sad' TO 'unhappy'");
        assertThat(plan.diff().isEmpty()).isTrue();
    }

    @Test
    void aRenameThatDoesNotFitTheOldSchemaIsRefused() {
        Catalog schema = RulesTest.catalog(T);
        PlanOptions options = PlanOptions.direct().withRenames(List.of(new Rename.OfColumn(TABLE, "missing", "c")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Planner.plan(schema, schema, options))
                .withMessage("A confirmed rename does not fit the old schema: column \"missing\" does not exist");
    }

    @Test
    void onlyRenameCandidatesTurnIntoRenames() {
        SchemaDiff diff = SchemaDiff.compare(RulesTest.catalog(T), RulesTest.catalog("CREATE TABLE t (a int);"));
        assertThat(Rename.of(diff.changes().get(0))).isEqualTo(Optional.empty());
    }

    // ---------------------------------------------------------------- options

    @Test
    void theLockTimeoutCanBeSetAndIsChecked() {
        MigrationPlan plan = Planner.plan(RulesTest.catalog(T), RulesTest.catalog("CREATE TABLE t (a int);"), PlanOptions.safe().withLockTimeout("750ms"));
        assertThat(plan.steps().get(0).sql()).isEqualTo("SET lock_timeout = '750ms'");
        assertThat(plan.route()).isEqualTo(PlanOptions.Route.SAFE);
        assertThatIllegalArgumentException().isThrownBy(() -> PlanOptions.safe().withLockTimeout("5s'; DROP TABLE t; --"));
        assertThat(PlanOptions.direct().lockTimeout()).isEqualTo(PlanOptions.DEFAULT_LOCK_TIMEOUT);
    }

    // ---------------------------------------------------------------- golden plans

    @Test
    void theEvolutionPlansMatchTheReviewedGoldenFiles() throws IOException {
        Catalog before = fixture("before_pg_dump_16.sql");
        Catalog after = fixture("after_pg_dump_16.sql");
        List<Rename> renames = SchemaDiff.compare(before, after).changes().stream().flatMap(c -> Rename.of(c).stream()).toList();
        golden("expected-plan-direct.sql", Planner.plan(before, after).toSql());
        golden("expected-plan-safe.sql", Planner.plan(before, after, PlanOptions.safe().withRenames(renames)).toSql());
    }

    private static void golden(String file, String actual) throws IOException {
        Path path = Path.of("src/test/resources/golden/evolution", file);
        if (Boolean.getBoolean("golden.update")) {
            Files.writeString(path, actual, StandardCharsets.UTF_8);
        }
        assertThat(actual).isEqualTo(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static Catalog fixture(String file) throws IOException {
        try (InputStream in = PlannerTest.class.getResourceAsStream("/golden/evolution/" + file)) {
            assertThat(in).as("test resource %s", file).isNotNull();
            return RulesTest.catalog(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
