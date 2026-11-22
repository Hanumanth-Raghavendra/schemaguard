package io.github.hanumanthraghavendra.schemaguard.api.auth;

import io.github.hanumanthraghavendra.schemaguard.api.persistence.Database;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Accounts and refresh tokens in PostgreSQL (tables {@code account} and {@code refresh_token}, V2). */
public class AccountStore {

    private static final String FEATURE = "Accounts";

    private static final String ACCOUNT_COLUMNS = "id, email, password_hash, created_at, failed_logins, locked_until";
    private static final String TOKEN_COLUMNS =
            "token_hash, account_id, family, issued_at, expires_at, session_expires_at, used_at, revoked_at";

    private final Database database;
    private final JdbcClient jdbc;

    public AccountStore(Database database) {
        this.database = database;
        this.jdbc = database.jdbc();
    }

    /** Runs the work in one transaction; what it returns is committed, an exception rolls it all back. */
    public <T> T transaction(Supplier<T> work) {
        return database.transaction(FEATURE, work);
    }

    /** Adds an account; false if an account with its email exists already. */
    public boolean create(Account account) {
        return database.run(
                FEATURE,
                () -> {
                    try {
                        jdbc.sql("INSERT INTO account (id, email, password_hash, created_at) VALUES (:id, :email, :hash, :created)")
                                .param("id", account.id())
                                .param("email", account.email())
                                .param("hash", account.passwordHash())
                                .param("created", time(account.createdAt()))
                                .update();
                        return true;
                    } catch (DuplicateKeyException e) {
                        return false;
                    }
                });
    }

    public Optional<Account> findByEmail(String email) {
        return database.run(
                FEATURE,
                () -> jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account WHERE email = :email")
                        .param("email", email)
                        .query(AccountStore::account)
                        .optional());
    }

    public Optional<Account> findById(UUID id) {
        return database.run(
                FEATURE,
                () -> jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account WHERE id = :id")
                        .param("id", id)
                        .query(AccountStore::account)
                        .optional());
    }

    /**
     * Counts a failed login. Failures count in a row: the count starts again at one when the last
     * failure was before {@code windowStart}.
     *
     * @return the failures in a row, this one included
     */
    public int recordFailure(UUID id, Instant now, Instant windowStart) {
        return database.run(
                FEATURE,
                () -> jdbc.sql(
                                """
                                UPDATE account SET
                                    failed_logins = CASE WHEN last_failed_at IS NULL OR last_failed_at < :windowStart
                                                         THEN 1 ELSE failed_logins + 1 END,
                                    last_failed_at = :now
                                WHERE id = :id
                                RETURNING failed_logins
                                """)
                        .param("id", id)
                        .param("now", time(now))
                        .param("windowStart", time(windowStart))
                        .query(Integer.class)
                        .optional()
                        .orElse(0));
    }

    public void lock(UUID id, Instant until) {
        database.run(FEATURE, () -> jdbc.sql("UPDATE account SET locked_until = :until WHERE id = :id")
                .param("id", id)
                .param("until", time(until))
                .update());
    }

    /** Forgets earlier failures after a successful login. */
    public void clearFailures(UUID id) {
        database.run(FEATURE, () -> jdbc.sql(
                        "UPDATE account SET failed_logins = 0, last_failed_at = NULL, locked_until = NULL WHERE id = :id")
                .param("id", id)
                .update());
    }

    public void updatePasswordHash(UUID id, String hash) {
        database.run(FEATURE, () -> jdbc.sql("UPDATE account SET password_hash = :hash WHERE id = :id")
                .param("id", id)
                .param("hash", hash)
                .update());
    }

    /** Deletes an account and, through the foreign key, all its refresh tokens. */
    public boolean delete(UUID id) {
        return database.run(FEATURE, () -> jdbc.sql("DELETE FROM account WHERE id = :id").param("id", id).update() > 0);
    }

    public void addToken(RefreshToken token) {
        database.run(
                FEATURE,
                () -> jdbc.sql(
                                """
                                INSERT INTO refresh_token (token_hash, account_id, family, issued_at, expires_at, session_expires_at)
                                VALUES (:hash, :account, :family, :issued, :expires, :sessionExpires)
                                """)
                        .param("hash", token.hash())
                        .param("account", token.accountId())
                        .param("family", token.family())
                        .param("issued", time(token.issuedAt()))
                        .param("expires", time(token.expiresAt()))
                        .param("sessionExpires", time(token.sessionExpiresAt()))
                        .update());
    }

    /**
     * The token with this hash, locked until the transaction ends, so two requests that present the
     * same token are served one after the other. Call it inside {@link #transaction}.
     */
    public Optional<RefreshToken> lockToken(String hash) {
        return database.run(
                FEATURE,
                () -> jdbc.sql("SELECT " + TOKEN_COLUMNS + " FROM refresh_token WHERE token_hash = :hash FOR UPDATE")
                        .param("hash", hash)
                        .query(AccountStore::token)
                        .optional());
    }

    public Optional<RefreshToken> findToken(String hash) {
        return database.run(
                FEATURE,
                () -> jdbc.sql("SELECT " + TOKEN_COLUMNS + " FROM refresh_token WHERE token_hash = :hash")
                        .param("hash", hash)
                        .query(AccountStore::token)
                        .optional());
    }

    /** Marks a token exchanged, unless it was already. */
    public void markUsed(String hash, Instant now) {
        database.run(FEATURE, () -> jdbc.sql("UPDATE refresh_token SET used_at = :now WHERE token_hash = :hash AND used_at IS NULL")
                .param("hash", hash)
                .param("now", time(now))
                .update());
    }

    /** Ends a session: every token of the family stops working. */
    public int revokeFamily(UUID family, Instant now) {
        return database.run(FEATURE, () -> jdbc.sql(
                        "UPDATE refresh_token SET revoked_at = :now WHERE family = :family AND revoked_at IS NULL")
                .param("family", family)
                .param("now", time(now))
                .update());
    }

    /** Removes tokens that can no longer be used or replayed; returns how many. */
    public int deleteExpiredTokens(Instant now) {
        return database.run(FEATURE, () -> jdbc.sql("DELETE FROM refresh_token WHERE expires_at <= :now")
                .param("now", time(now))
                .update());
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Instant required(ResultSet row, String column) throws SQLException {
        Instant value = instant(row, column);
        if (value == null) {
            throw new SQLException(column + " is NOT NULL but came back null");
        }
        return value;
    }

    private static Account account(ResultSet row, int number) throws SQLException {
        return new Account(
                row.getObject("id", UUID.class),
                row.getString("email"),
                row.getString("password_hash"),
                required(row, "created_at"),
                row.getInt("failed_logins"),
                instant(row, "locked_until"));
    }

    private static RefreshToken token(ResultSet row, int number) throws SQLException {
        return new RefreshToken(
                row.getString("token_hash"),
                row.getObject("account_id", UUID.class),
                row.getObject("family", UUID.class),
                required(row, "issued_at"),
                required(row, "expires_at"),
                required(row, "session_expires_at"),
                instant(row, "used_at"),
                instant(row, "revoked_at"));
    }
}
