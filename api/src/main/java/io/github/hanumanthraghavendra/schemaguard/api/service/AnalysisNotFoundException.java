package io.github.hanumanthraghavendra.schemaguard.api.service;

/** No saved analysis has the id, or it has expired. Answered with 404. */
public class AnalysisNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AnalysisNotFoundException(String message) {
        super(message);
    }
}
