package io.github.hanumanthraghavendra.schemaguard.api.auth;

/** Answered 422: the email or password cannot be used for an account. The message is shown to the person. */
public class InvalidAccountDetailsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidAccountDetailsException(String message) {
        super(message);
    }
}
