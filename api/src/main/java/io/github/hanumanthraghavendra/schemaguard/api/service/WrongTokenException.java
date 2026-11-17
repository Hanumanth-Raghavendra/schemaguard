package io.github.hanumanthraghavendra.schemaguard.api.service;

/** A delete token that does not belong to the analysis. Answered with 403. */
public class WrongTokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WrongTokenException(String message) {
        super(message);
    }
}
