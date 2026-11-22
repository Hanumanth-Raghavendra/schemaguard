package io.github.hanumanthraghavendra.schemaguard.api.auth;

/** Answered 401: the email or password is wrong. The message is shown to the person. */
public class LoginFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LoginFailedException(String message) {
        super(message);
    }
}
