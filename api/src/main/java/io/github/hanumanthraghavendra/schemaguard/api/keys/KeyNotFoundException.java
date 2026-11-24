package io.github.hanumanthraghavendra.schemaguard.api.keys;

/** Answered 404: the account has no API key with this id. The message is shown to the person. */
public class KeyNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public KeyNotFoundException(String message) {
        super(message);
    }
}
