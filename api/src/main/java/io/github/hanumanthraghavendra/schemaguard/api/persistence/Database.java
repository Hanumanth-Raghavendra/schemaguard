package io.github.hanumanthraghavendra.schemaguard.api.persistence;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
 */
public final class Database {

    private static final Logger LOG = LoggerFactory.getLogger(Database.class);

    private final JdbcClient jdbc;
    private final Flyway flyway;
    private final TransactionTemplate transactions;
    private final AtomicBoolean migrated = new AtomicBoolean();

    public Database(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
        this.flyway =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .validateMigrationNaming(true)
                        .load();
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
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
