package io.github.hanumanthraghavendra.schemaguard.api.auth;

import java.time.Duration;

/** Answered 429 with {@code Retry-After}: too many failed logins for this account. */
public class AccountLockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Duration retryAfter;

    public AccountLockedException(Duration retryAfter) {
        super(message(retryAfter));
        this.retryAfter = retryAfter;
    }

    /** How long until a login is accepted again, rounded up to whole seconds. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }

    private static String message(Duration wait) {
        long minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
        return "Too many failed logins for this account. Try again in " + minutes + (minutes == 1 ? " minute." : " minutes.");
    }
}
