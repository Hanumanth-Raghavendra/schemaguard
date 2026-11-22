package io.github.hanumanthraghavendra.schemaguard.api.auth;

/** Answered 409: an account with the email already exists. The message is shown to the person. */
public class EmailTakenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmailTakenException(String message) {
        super(message);
    }
}
