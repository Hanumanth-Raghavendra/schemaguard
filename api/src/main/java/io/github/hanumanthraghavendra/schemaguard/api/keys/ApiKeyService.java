package io.github.hanumanthraghavendra.schemaguard.api.keys;

import io.github.hanumanthraghavendra.schemaguard.api.auth.SessionEndedException;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.PersistenceUnavailableException;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.Secrets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * API keys: made, listed and deleted by a logged-in account, then sent by the command-line tool
 * and CI as {@code Authorization: Bearer sg_...}.
 *
 * <p>A key is {@code sg_} and 256 random bits in base64url, 46 characters. Its whole value is shown
 * once, when it is made; only its SHA-256 is stored, with its first characters as a hint. A plain
 * hash is enough here, unlike for passwords: a random 256-bit key cannot be guessed from its hash,
 * so there is nothing for a slow hash to protect. The fixed prefix and length let secret scanners
 * recognise a leaked key.
 */
@Service
public class ApiKeyService {

    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyService.class);

    public static final String PREFIX = "sg_";
    static final int LENGTH = PREFIX.length() + 43;
    static final int HINT_LENGTH = PREFIX.length() + 4;
    static final int MAX_KEYS = 10;
    static final int MAX_NAME = 80;
    static final Duration TOUCH_EVERY = Duration.ofMinutes(1);

    private final ObjectProvider<ApiKeyStore> stores;
    private final Clock clock;

    /**
     * A key just made.
     *
     * @param secret the whole key, for its owner to copy now: it is not stored
     */
    public record Created(ApiKey key, String secret) {
        @Override
        public String toString() {
            return "Created[" + key.id() + "]";
        }
    }

    @Autowired
    public ApiKeyService(ObjectProvider<ApiKeyStore> stores) {
        this(stores, Clock.systemUTC());
    }

    ApiKeyService(ObjectProvider<ApiKeyStore> stores, Clock clock) {
        this.stores = stores;
        this.clock = clock;
    }

    /** True if a bearer token is meant as an API key rather than an access token. */
    public static boolean isKey(String token) {
        return token.startsWith(PREFIX);
    }

    public List<ApiKey> list(UUID account) {
        return store().list(account);
    }

    public Created create(UUID account, String name) {
        ApiKeyStore store = store();
        String clean = name(name);
        String secret = PREFIX + Secrets.newToken();
        ApiKey key = new ApiKey(UUID.randomUUID(), account, clean, secret.substring(0, HINT_LENGTH), now(), null);
        store.transaction(() -> {
            if (!store.lockAccount(account)) {
                throw new SessionEndedException("This account no longer exists.");
            }
            if (store.count(account) >= MAX_KEYS) {
                throw new KeyLimitException("This account has " + MAX_KEYS + " API keys, the most it can have. Delete one to make room.");
            }
            store.insert(key, Secrets.hash(secret));
            return null;
        });
        LOG.info("API key {} made for account {}", key.id(), account);
        return new Created(key, secret);
    }

    public void delete(UUID account, UUID id) {
        if (!store().delete(account, id)) {
            throw new KeyNotFoundException("This account has no such API key.");
        }
        LOG.info("API key {} of account {} deleted", id, account);
    }

    /**
     * The key a bearer token names, and a note that it was used.
     *
     * @return empty if no key has this value (never made, deleted, or its account deleted)
     * @throws PersistenceUnavailableException if the database cannot be reached
     */
    public Optional<ApiKey> authenticate(String token) {
        if (!isKey(token) || token.length() != LENGTH) {
            return Optional.empty();
        }
        ApiKeyStore store = store();
        Optional<ApiKey> found = store.findByHash(Secrets.hash(token));
        found.ifPresent(key -> {
            Instant now = now();
            store.touch(key.id(), now, now.minus(TOUCH_EVERY));
        });
        return found;
    }

    /** A key's name as stored: trimmed, 1 to 80 characters, one line. */
    static String name(String name) {
        String clean = name.strip();
        int length = clean.codePointCount(0, clean.length());
        if (length == 0 || length > MAX_NAME || clean.chars().anyMatch(Character::isISOControl)) {
            throw new KeyLimitException("Name the key in 1 to " + MAX_NAME + " characters, on one line, such as \"GitHub Actions\".");
        }
        return clean;
    }

    private ApiKeyStore store() {
        ApiKeyStore store = stores.getIfAvailable();
        if (store == null) {
            throw new PersistenceUnavailableException("API keys are switched off on this server.");
        }
        return store;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
