package io.github.hanumanthraghavendra.schemaguard.api.auth;

/**
 * Answered 403: the password typed to confirm an action on the logged-in account is wrong. Not 401,
 * which would tell the client its access token failed and send it to refresh the session.
 */
public class WrongPasswordException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WrongPasswordException(String message) {
        super(message);
    }
}
