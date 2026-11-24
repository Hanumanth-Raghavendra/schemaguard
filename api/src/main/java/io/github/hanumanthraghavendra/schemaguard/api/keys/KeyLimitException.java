package io.github.hanumanthraghavendra.schemaguard.api.keys;

/** Answered 422: the account has all the keys it may have, or the name cannot be used. The message is shown to the person. */
public class KeyLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public KeyLimitException(String message) {
        super(message);
    }
}
