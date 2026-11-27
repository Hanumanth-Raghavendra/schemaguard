package io.github.hanumanthraghavendra.schemaguard.api.persistence;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The service's one PostgreSQL database, shared by saved analyses and accounts.
 *
 * <p>Nothing here runs at startup. The schema is migrated on the first operation that needs the
 * database, and again on the next one if that failed. A database that is asleep (Neon's free tier
 * suspends after a few idle minutes), down or misconfigured therefore costs the features that need
 * it a 503, never the whole service.
 *
 * <p>Migrations hold Flyway's lock as a session-level advisory lock, not inside a transaction.
 * Under Flyway's default transactional lock, {@code CREATE INDEX CONCURRENTLY} waits forever for
 * Flyway's own open transaction (D-115). A session-level lock needs a direct connection, never a
 * transaction pooler, so migrations may connect elsewhere than the requests do (D-118). Every
 * migration statement also has a lock timeout: one that cannot get its lock fails fast, and the
 * operation gets a 503, instead of every request waiting behind it.
 */
public final class Database {

    private static final Logger LOG = LoggerFactory.getLogger(Database.class);

    /** The lock timeout of migrations when none is given. */
    static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(10);

    private final JdbcClient jdbc;
    private final Flyway flyway;
    private final TransactionTemplate transactions;
    private final AtomicBoolean migrated = new AtomicBoolean();

    /** Requests and migrations on one data source, as in tests; it must reach the database directly. */
    public Database(DataSource dataSource) {
        this(dataSource, dataSource, DEFAULT_LOCK_TIMEOUT);
    }

    /**
     * @param dataSource  for the requests
     * @param migrations  for the migrations: a direct connection, never a transaction pooler, and
     *                    not a pool the requests share, as the lock timeout is set per connection
     * @param lockTimeout how long a migration statement may wait for a lock
     */
    public Database(DataSource dataSource, DataSource migrations, Duration lockTimeout) {
        this.jdbc = JdbcClient.create(dataSource);
        this.flyway = flyway(migrations, lockTimeout, false, "classpath:db/migration").load();
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /**
     * Flyway as this service runs it, apart for tests.
     *
     * @param transactionalLock true only for tests that show what Flyway's default does (D-115)
     */
    static FluentConfiguration flyway(DataSource migrations, Duration lockTimeout, boolean transactionalLock, String... locations) {
        return Flyway.configure()
                .configuration(Map.of("flyway.postgresql.transactional.lock", Boolean.toString(transactionalLock)))
                .dataSource(migrations)
                .locations(locations)
                .initSql("SET lock_timeout = '" + Math.max(1, lockTimeout.toMillis()) + "ms'")
                .validateMigrationNaming(true);
    }

    /** Statements run through this client take part in the transaction of {@link #transaction}. */
    public JdbcClient jdbc() {
        return jdbc;
    }

    /**
     * Runs an operation, migrating the schema first if that has not happened yet.
     *
     * @param feature what the operation is for, in the plural ("Saved analyses"), for the message a
     *                client sees when the database cannot be reached
     * @throws PersistenceUnavailableException if the database cannot be reached or refuses the work
     */
    public <T> T run(String feature, Supplier<T> operation) {
        try {
            migrate();
            return operation.get();
        } catch (DataAccessException | FlywayException | TransactionException e) {
            throw unavailable(feature, e);
        }
    }

    /** Like {@link #run}, inside one transaction: everything commits together, or nothing does. */
    public <T> T transaction(String feature, Supplier<T> operation) {
        return run(feature, () -> transactions.execute(status -> operation.get()));
    }

    private void migrate() {
        if (!migrated.get()) {
            synchronized (this) {
                if (!migrated.get()) {
                    flyway.migrate();
                    migrated.set(true);
                }
            }
        }
    }

    private static PersistenceUnavailableException unavailable(String feature, Exception error) {
        // The cause is logged without any request content; the message may name the host, never data.
        LOG.warn("{} are unavailable: {}", feature, rootMessage(error));
        return new PersistenceUnavailableException(feature + " are unavailable right now; try again later.", error);
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
