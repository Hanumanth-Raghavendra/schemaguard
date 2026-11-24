package io.github.hanumanthraghavendra.schemaguard.api.keys;

import io.github.hanumanthraghavendra.schemaguard.api.persistence.Database;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** API keys in PostgreSQL (table {@code api_key}, V4). */
public class ApiKeyStore {

    private static final String FEATURE = "API keys";
    private static final String COLUMNS = "id, account_id, name, hint, created_at, last_used_at";

    private final Database database;
    private final JdbcClient jdbc;

    public ApiKeyStore(Database database) {
        this.database = database;
        this.jdbc = database.jdbc();
    }

    public <T> T transaction(Supplier<T> work) {
        return database.transaction(FEATURE, work);
    }

    /** Locks the account's row until the transaction ends; false if the account no longer exists. */
    public boolean lockAccount(UUID account) {
        return database.run(FEATURE, () -> jdbc.sql("SELECT id FROM account WHERE id = :account FOR UPDATE")
                .param("account", account)
                .query(UUID.class)
                .optional()
                .isPresent());
    }

    public List<ApiKey> list(UUID account) {
        return database.run(
                FEATURE,
                () -> jdbc.sql("SELECT " + COLUMNS + " FROM api_key WHERE account_id = :account ORDER BY created_at DESC, name")
                        .param("account", account)
                        .query((row, number) -> key(row))
                        .list());
    }

    public int count(UUID account) {
        return database.run(FEATURE, () -> jdbc.sql("SELECT count(*) FROM api_key WHERE account_id = :account")
                .param("account", account)
                .query(Integer.class)
                .single());
    }

    public void insert(ApiKey key, String keyHash) {
        database.run(
                FEATURE,
                () -> jdbc.sql(
                                """
                                INSERT INTO api_key (id, account_id, name, hint, key_hash, created_at)
                                VALUES (:id, :account, :name, :hint, :hash, :created)
                                """)
                        .param("id", key.id())
                        .param("account", key.accountId())
                        .param("name", key.name())
                        .param("hint", key.hint())
                        .param("hash", keyHash)
                        .param("created", time(key.createdAt()))
                        .update());
    }

    public boolean delete(UUID account, UUID id) {
        return database.run(FEATURE, () -> jdbc.sql("DELETE FROM api_key WHERE id = :id AND account_id = :account")
                .param("id", id)
                .param("account", account)
                .update() > 0);
    }

    public Optional<ApiKey> findByHash(String keyHash) {
        return database.run(
                FEATURE,
                () -> jdbc.sql("SELECT " + COLUMNS + " FROM api_key WHERE key_hash = :hash")
                        .param("hash", keyHash)
                        .query((row, number) -> key(row))
                        .optional());
    }

    /** Records a use, unless one was recorded since {@code since}: at most one write a minute per key. */
    public void touch(UUID id, Instant now, Instant since) {
        database.run(FEATURE, () -> jdbc.sql(
                        "UPDATE api_key SET last_used_at = :now WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :since)")
                .param("id", id)
                .param("now", time(now))
                .param("since", time(since))
                .update());
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static ApiKey key(ResultSet row) throws SQLException {
        Instant created = instant(row, "created_at");
        if (created == null) {
            throw new SQLException("created_at is NOT NULL but came back null");
        }
        return new ApiKey(
                row.getObject("id", UUID.class),
                row.getObject("account_id", UUID.class),
                row.getString("name"),
                row.getString("hint"),
                created,
                instant(row, "last_used_at"));
    }
}
