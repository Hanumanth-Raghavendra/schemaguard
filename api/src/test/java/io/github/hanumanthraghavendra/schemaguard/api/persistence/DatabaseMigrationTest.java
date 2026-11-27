package io.github.hanumanthraghavendra.schemaguard.api.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The service's migrations followed by {@code CREATE INDEX CONCURRENTLY}, as SG-R101 advises, on
 * a real PostgreSQL database (D-115, D-118). Each test gets a fresh database, and a timeout far
 * above the lock timeout, so a hang fails the test instead of the build.
 */
@EnabledIfEnvironmentVariable(named = TestDatabase.SWITCH, matches = "1")
class DatabaseMigrationTest {

    private static final String[] LOCATIONS = {"classpath:db/migration", "classpath:db/concurrently"};

    private TestDatabase database;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void createDatabase() {
        database = TestDatabase.create("sg_migrate_test");
        dataSource = new DriverManagerDataSource(database.url(), database.user(), database.password());
    }

    @AfterEach
    void dropDatabase() {
        database.drop();
    }

    @Test
    void underTheServicesSessionLockAConcurrentIndexIsBuilt() {
        Flyway flyway = Database.flyway(dataSource, Duration.ofSeconds(5), false, LOCATIONS).load();

        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> flyway.migrate());

        assertThat(JdbcClient.create(dataSource)
                        .sql("SELECT indisvalid FROM pg_index WHERE indexrelid = 'analysis_created_at_idx'::regclass")
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("900");
    }

    @Test
    void underFlywaysDefaultLockTheBuildWaitsForFlywayItselfUntilTheLockTimeoutEndsIt() {
        // The bug of D-115, kept as a test: Flyway's lock transaction stays open on one connection,
        // and the build waits for it on another. Without a lock timeout this would never end.
        Flyway flyway = Database.flyway(dataSource, Duration.ofSeconds(2), true, LOCATIONS).load();
        long started = System.nanoTime();

        assertThatThrownBy(() -> assertTimeoutPreemptively(Duration.ofSeconds(60), () -> flyway.migrate()))
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("lock timeout");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void theServicesOwnDatabaseMigratesWithTheLockTimeoutOnItsOwnConnections() {
        DriverManagerDataSource requests = new DriverManagerDataSource(database.url(), database.user(), database.password());
        Database service = new Database(requests, dataSource, Duration.ofMillis(1500));

        assertThat(service.run("Tests", () -> service.jdbc().sql("SHOW lock_timeout").query(String.class).single()))
                .as("a request's connection keeps the server's default")
                .isEqualTo("0");
        assertThat(JdbcClient.create(dataSource)
                        .sql("SELECT count(*) FROM flyway_schema_history WHERE success")
                        .query(Long.class)
                        .single())
                .isEqualTo(4);
    }
}
