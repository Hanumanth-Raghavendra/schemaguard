package io.github.hanumanthraghavendra.schemaguard.api.auth;

/**
 * Answered 401: the session is over (no, expired, revoked or reused refresh token, or the account is
 * gone). The message is shown to the person.
 */
public class SessionEndedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SessionEndedException(String message) {
        super(message);
    }
}
