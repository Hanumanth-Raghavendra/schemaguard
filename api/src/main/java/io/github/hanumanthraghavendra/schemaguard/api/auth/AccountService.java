package io.github.hanumanthraghavendra.schemaguard.api.auth;

import io.github.hanumanthraghavendra.schemaguard.api.config.ApiProperties;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.PersistenceUnavailableException;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.Secrets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Sign-up, login, sessions and account deletion.
 *
 * <p>A session is a family of refresh tokens that starts at sign-up or login. Each refresh
 * exchanges the presented token for a new one in the same family (rotation). A token that comes
 * back after it was exchanged means two parties hold it, one of them not the owner, so the whole
 * family is revoked (reuse detection, RFC 9700 section 4.14.2) — except within a short grace
 * period, which is two tabs of one browser refreshing at the same moment.
 *
 * <p>Failed logins in a row are counted per account: after {@value #MAX_FAILURES} within an hour,
 * the account refuses logins for a minute, then twice as long after each further failure, up to
 * a quarter of an hour. An email without an account costs the same password check as a wrong
 * password, so timing does not tell which emails have accounts.
 */
@Service
public class AccountService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountService.class);

    static final int MAX_FAILURES = 5;
    static final Duration FAILURE_WINDOW = Duration.ofHours(1);
    static final Duration FIRST_LOCK = Duration.ofMinutes(1);
    static final Duration LONGEST_LOCK = Duration.ofMinutes(15);
    // Secrets.newToken() makes 43 characters; anything far longer is not a token and is not hashed.
    private static final int MAX_TOKEN_LENGTH = 128;

    static final String WRONG_LOGIN = "The email or password is wrong.";
    static final String SESSION_ENDED = "Your session has ended; log in again.";

    private final ObjectProvider<AccountStore> stores;
    private final PasswordPolicy policy;
    private final PasswordHasher hasher;
    private final AccessTokens tokens;
    private final ApiProperties.Auth settings;
    private final Clock clock;

    /**
     * A session that was just started or renewed.
     *
     * @param refreshToken  the new refresh token, for the cookie and nothing else
     * @param refreshMaxAge how long the cookie should be kept: until the token expires
     */
    public record Grant(Account account, AccessTokens.Issued access, String refreshToken, Duration refreshMaxAge) {
        @Override
        public String toString() {
            return "Grant[" + account + "]";
        }
    }

    /** What exchanging a refresh token found; decided inside the transaction, answered after it. */
    private sealed interface Exchange {
        record Renewed(Account account, String refreshToken, RefreshToken stored) implements Exchange {}

        record Refused() implements Exchange {}
    }

    @Autowired
    public AccountService(
            ObjectProvider<AccountStore> stores,
            PasswordPolicy policy,
            PasswordHasher hasher,
            AccessTokens tokens,
            ApiProperties properties) {
        this(stores, policy, hasher, tokens, properties.auth(), Clock.systemUTC());
    }

    AccountService(
            ObjectProvider<AccountStore> stores,
            PasswordPolicy policy,
            PasswordHasher hasher,
            AccessTokens tokens,
            ApiProperties.Auth settings,
            Clock clock) {
        this.stores = stores;
        this.policy = policy;
        this.hasher = hasher;
        this.tokens = tokens;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Creates an account and logs it in.
     *
     * @param previousRefresh the refresh cookie the browser still had, if any; its session ends
     */
    public Grant signUp(String email, String password, @Nullable String previousRefresh) {
        AccountStore store = store();
        String normalized = policy.normalizeEmail(email);
        policy.check(password, normalized);
        Account account = new Account(UUID.randomUUID(), normalized, hasher.hash(password), now(), 0, null);
        if (!store.create(account)) {
            throw new EmailTakenException("An account with this email already exists. Log in instead.");
        }
        LOG.info("Account {} created", account.id());
        endSession(store, previousRefresh);
        return startSession(store, account);
    }

    /** Logs in with email and password. */
    public Grant logIn(String email, String password, @Nullable String previousRefresh) {
        AccountStore store = store();
        String normalized = policy.normalizeEmail(email);
        Optional<Account> found = store.findByEmail(normalized);
        if (found.isEmpty()) {
            hasher.matchNothing(password);
            throw new LoginFailedException(WRONG_LOGIN);
        }
        Account account = verify(store, found.get(), password, LoginFailedException::new, WRONG_LOGIN);
        endSession(store, previousRefresh);
        return startSession(store, account);
    }

    /**
     * Exchanges a refresh token for a new one and a new access token.
     *
     * @throws SessionEndedException if the token is missing, unknown, expired, revoked or reused
     */
    public Grant refresh(@Nullable String refreshToken) {
        if (!looksLikeToken(refreshToken)) {
            throw new SessionEndedException(SESSION_ENDED);
        }
        AccountStore store = store();
        String hash = Secrets.hash(refreshToken);
        Instant now = now();
        Exchange exchange = store.transaction(() -> exchange(store, hash, now));
        if (exchange instanceof Exchange.Renewed renewed) {
            return grant(renewed.account(), renewed.refreshToken(), renewed.stored(), now);
        }
        throw new SessionEndedException(SESSION_ENDED);
    }

    /** Ends the session the refresh token belongs to. Nothing to end is not an error. */
    public void logOut(@Nullable String refreshToken) {
        AccountStore store = stores.getIfAvailable();
        if (store != null) {
            endSession(store, refreshToken);
        }
    }

    /**
     * The account an access token names.
     *
     * @throws SessionEndedException if it was deleted since the token was issued
     */
    public Account account(UUID id) {
        return store().findById(id).orElseThrow(() -> new SessionEndedException("This account no longer exists."));
    }

    /** Deletes the account and all its sessions, after checking its password once more. */
    public void delete(UUID id, String password) {
        AccountStore store = store();
        Account account = account(id);
        verify(store, account, password, WrongPasswordException::new, "The password is wrong.");
        store.delete(id);
        LOG.info("Account {} deleted", id);
    }

    /** How long logins are refused after this many failures in a row. */
    static Duration lockFor(int failures) {
        int doublings = Math.min(Math.max(failures - MAX_FAILURES, 0), 10);
        Duration lock = FIRST_LOCK.multipliedBy(1L << doublings);
        return lock.compareTo(LONGEST_LOCK) > 0 ? LONGEST_LOCK : lock;
    }

    private Exchange exchange(AccountStore store, String hash, Instant now) {
        Optional<RefreshToken> found = store.lockToken(hash);
        if (found.isEmpty()) {
            return new Exchange.Refused();
        }
        RefreshToken token = found.get();
        if (token.revokedAt() != null || !now.isBefore(token.expiresAt()) || !now.isBefore(token.sessionExpiresAt())) {
            return new Exchange.Refused();
        }
        if (token.usedAt() != null && Duration.between(token.usedAt(), now).compareTo(settings.refreshReuseGrace()) > 0) {
            // Returned, not thrown: the revocation must commit.
            store.revokeFamily(token.family(), now);
            LOG.warn("A refresh token of account {} came back {} after it was exchanged; the session is ended",
                    token.accountId(), Duration.between(token.usedAt(), now));
            return new Exchange.Refused();
        }
        Optional<Account> account = store.findById(token.accountId());
        if (account.isEmpty()) {
            return new Exchange.Refused();
        }
        store.markUsed(hash, now);
        String raw = Secrets.newToken();
        RefreshToken next = new RefreshToken(
                Secrets.hash(raw),
                token.accountId(),
                token.family(),
                now,
                earliest(now.plus(settings.refreshTokenLifetime()), token.sessionExpiresAt()),
                token.sessionExpiresAt(),
                null,
                null);
        store.addToken(next);
        return new Exchange.Renewed(account.get(), raw, next);
    }

    /**
     * Checks the password, counting failures and locking the account after too many.
     *
     * @param wrong   makes the exception for a wrong password
     * @param message its message
     */
    private Account verify(
            AccountStore store, Account account, String password, Function<String, RuntimeException> wrong, String message) {
        Instant now = now();
        Instant lockedUntil = account.lockedUntil();
        if (lockedUntil != null && now.isBefore(lockedUntil)) {
            throw new AccountLockedException(Duration.between(now, lockedUntil));
        }
        if (!hasher.matches(password, account.passwordHash())) {
            int failures = store.recordFailure(account.id(), now, now.minus(FAILURE_WINDOW));
            if (failures >= MAX_FAILURES) {
                Duration lock = lockFor(failures);
                store.lock(account.id(), now.plus(lock));
                LOG.info("Account {} refuses logins for {} after {} failed in a row", account.id(), lock, failures);
                throw new AccountLockedException(lock);
            }
            throw wrong.apply(message);
        }
        if (account.failedLogins() > 0 || lockedUntil != null) {
            store.clearFailures(account.id());
        }
        if (hasher.needsRehash(account.passwordHash())) {
            store.updatePasswordHash(account.id(), hasher.hash(password));
        }
        return account;
    }

    private Grant startSession(AccountStore store, Account account) {
        Instant now = now();
        Instant sessionEnd = now.plus(settings.sessionLifetime());
        String raw = Secrets.newToken();
        RefreshToken token = new RefreshToken(
                Secrets.hash(raw),
                account.id(),
                UUID.randomUUID(),
                now,
                earliest(now.plus(settings.refreshTokenLifetime()), sessionEnd),
                sessionEnd,
                null,
                null);
        store.addToken(token);
        return grant(account, raw, token, now);
    }

    private void endSession(AccountStore store, @Nullable String refreshToken) {
        if (looksLikeToken(refreshToken)) {
            store.findToken(Secrets.hash(refreshToken)).ifPresent(token -> store.revokeFamily(token.family(), now()));
        }
    }

    private Grant grant(Account account, String refreshToken, RefreshToken stored, Instant now) {
        return new Grant(account, tokens.issue(account.id()), refreshToken, Duration.between(now, stored.expiresAt()));
    }

    private AccountStore store() {
        AccountStore store = stores.getIfAvailable();
        if (store == null) {
            throw new PersistenceUnavailableException("Accounts are switched off on this server.");
        }
        return store;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static boolean looksLikeToken(@Nullable String token) {
        return token != null && !token.isBlank() && token.length() <= MAX_TOKEN_LENGTH;
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
