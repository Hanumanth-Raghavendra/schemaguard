package io.github.hanumanthraghavendra.schemaguard.api.persistence;

/** Permalinks are switched off, or the database cannot be reached. Answered with 503. */
public class PersistenceUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PersistenceUnavailableException(String message) {
        super(message);
    }

    public PersistenceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
