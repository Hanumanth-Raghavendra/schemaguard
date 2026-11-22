package io.github.hanumanthraghavendra.schemaguard.api.auth;

/** Answered 503: too many passwords are being hashed at once. The message is shown to the person. */
public class BusyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BusyException(String message) {
        super(message);
    }
}
