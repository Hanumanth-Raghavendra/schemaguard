package io.github.hanumanthraghavendra.schemaguard.api.persistence;

/** The saved analyses take all the room the service allows. Answered with 507. */
public class StorageFullException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StorageFullException(String message) {
        super(message);
    }
}
