package io.github.hanumanthraghavendra.schemaguard.api.service;

/** An input larger than the service accepts. Answered with 413. */
public class LimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LimitExceededException(String message) {
        super(message);
    }
}
