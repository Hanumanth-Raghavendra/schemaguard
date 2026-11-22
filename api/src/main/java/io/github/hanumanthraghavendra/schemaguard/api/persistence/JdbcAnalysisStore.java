package io.github.hanumanthraghavendra.schemaguard.api.persistence;

import io.github.hanumanthraghavendra.schemaguard.api.config.ApiProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Saved analyses in PostgreSQL. The {@link Database} migrates the schema on first use, so a
 * database that is asleep or down costs the permalink endpoints a 503, never the whole service.
 */
public class JdbcAnalysisStore implements AnalysisStore {

    private static final String FEATURE = "Saved analyses";

    private final Database database;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final long maxTotalBytes;

    public JdbcAnalysisStore(Database database, ApiProperties.Persistence settings, Clock clock) {
        this.database = database;
        this.jdbc = database.jdbc();
        this.clock = clock;
        this.maxTotalBytes = settings.maxTotalBytes();
    }

    @Override
    public void save(Saved analysis, String tokenHash) {
        long size = (long) analysis.request().length() + analysis.result().length();
        run(
                () -> {
                    long used =
                            jdbc.sql("SELECT coalesce(sum(size_bytes), 0) FROM analysis WHERE expires_at > :now")
                                    .param("now", now())
                                    .query(Long.class)
                                    .single();
                    if (used + size > maxTotalBytes) {
                        throw new StorageFullException("The service has no room for more saved analyses right now; try again later.");
                    }
                    jdbc.sql(
                                    """
                                    INSERT INTO analysis
                                        (id, kind, request, result, engine_version, size_bytes, delete_token_hash, created_at, expires_at)
                                    VALUES
                                        (:id, :kind, CAST(:request AS json), CAST(:result AS json), :version, :size, :hash, :created, :expires)
                                    """)
                            .param("id", analysis.id())
                            .param("kind", analysis.kind())
                            .param("request", analysis.request())
                            .param("result", analysis.result())
                            .param("version", analysis.engineVersion())
                            .param("size", size)
                            .param("hash", tokenHash)
                            .param("created", time(analysis.createdAt()))
                            .param("expires", time(analysis.expiresAt()))
                            .update();
                    return null;
                });
    }

    @Override
    public Optional<Saved> find(String id) {
        return run(
                () ->
                        jdbc.sql(
                                        """
                                        SELECT id, kind, request, result, engine_version, created_at, expires_at
                                        FROM analysis WHERE id = :id AND expires_at > :now
                                        """)
                                .param("id", id)
                                .param("now", now())
                                .query(JdbcAnalysisStore::saved)
                                .optional());
    }

    @Override
    public Deletion delete(String id, String tokenHash) {
        return run(
                () -> {
                    int deleted =
                            jdbc.sql("DELETE FROM analysis WHERE id = :id AND delete_token_hash = :hash AND expires_at > :now")
                                    .param("id", id)
                                    .param("hash", tokenHash)
                                    .param("now", now())
                                    .update();
                    if (deleted > 0) {
                        return Deletion.DELETED;
                    }
                    boolean exists =
                            jdbc.sql("SELECT count(*) FROM analysis WHERE id = :id AND expires_at > :now")
                                            .param("id", id)
                                            .param("now", now())
                                            .query(Long.class)
                                            .single()
                                    > 0;
                    return exists ? Deletion.WRONG_TOKEN : Deletion.NOT_FOUND;
                });
    }

    @Override
    public int deleteExpired() {
        return run(() -> jdbc.sql("DELETE FROM analysis WHERE expires_at <= :now").param("now", now()).update());
    }

    private <T> T run(Supplier<T> operation) {
        return database.run(FEATURE, operation);
    }

    private OffsetDateTime now() {
        return time(clock.instant());
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Saved saved(ResultSet row, int number) throws SQLException {
        return new Saved(
                row.getString("id"),
                row.getString("kind"),
                row.getString("request"),
                row.getString("result"),
                row.getString("engine_version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("expires_at", OffsetDateTime.class).toInstant());
    }
}
