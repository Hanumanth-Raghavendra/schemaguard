package io.github.hanumanthraghavendra.schemaguard.api.auth;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id password hashing with OWASP's first recommended setting: 19 MiB of memory, 2 passes,
 * 1 lane (Password Storage Cheat Sheet). The hash carries its own salt and settings, so a stronger
 * setting later applies to old accounts at their next login ({@link #needsRehash}).
 *
 * <p>Argon2 is memory-hard on purpose, which makes it a way to exhaust memory as well: on the
 * free 512 MB instance, a burst of logins hashing at once could take the heap. At most
 * {@value #CONCURRENT} hashes run at the same time; a request that waits more than
 * {@value #WAIT_SECONDS} seconds for its turn is answered 503.
 */
@Component
public class PasswordHasher {

    static final int CONCURRENT = 2;
    static final int WAIT_SECONDS = 10;

    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
    private final Semaphore turns = new Semaphore(CONCURRENT, true);
    private volatile String decoy;

    public String hash(String password) {
        return limited(() -> encoder.encode(password));
    }

    public boolean matches(String password, String hash) {
        return limited(() -> encoder.matches(password, hash));
    }

    /** True if the hash was made with weaker settings than the current ones. */
    public boolean needsRehash(String hash) {
        return encoder.upgradeEncoding(hash);
    }

    /**
     * Does the work of checking a password for an email that has no account, so the answer takes as
     * long as for a wrong password and does not tell which emails have accounts.
     */
    public void matchNothing(String password) {
        String target = decoy;
        if (target == null) {
            target = hash("decoy password, never an account's");
            decoy = target;
        }
        matches(password, target);
    }

    private <T> T limited(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = turns.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new BusyException("Too many people are logging in at once; try again in a moment.");
        }
        try {
            return work.get();
        } finally {
            turns.release();
        }
    }
}
