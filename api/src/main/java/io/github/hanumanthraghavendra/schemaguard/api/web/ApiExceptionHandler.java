package io.github.hanumanthraghavendra.schemaguard.api.web;

import io.github.hanumanthraghavendra.schemaguard.api.persistence.PersistenceUnavailableException;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.StorageFullException;
import io.github.hanumanthraghavendra.schemaguard.api.service.AnalysisNotFoundException;
import io.github.hanumanthraghavendra.schemaguard.api.service.InputRejectedException;
import io.github.hanumanthraghavendra.schemaguard.api.service.LimitExceededException;
import io.github.hanumanthraghavendra.schemaguard.api.service.WrongTokenException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error is an RFC 9457 problem document ({@code application/problem+json}) with a
 * {@code code} a client can switch on. Malformed JSON and failed validation are handled by
 * Spring's own base class and come back as 400.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InputRejectedException.class)
    ProblemDetail rejected(InputRejectedException e) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "Input cannot be analysed", e.getMessage(), "SG-API-422");
        problem.setProperty("diagnostics", e.diagnostics());
        return problem;
    }

    @ExceptionHandler(LimitExceededException.class)
    ProblemDetail tooLarge(LimitExceededException e) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "Content Too Large", e.getMessage(), "SG-API-413");
    }

    @ExceptionHandler(AnalysisNotFoundException.class)
    ProblemDetail notFound(AnalysisNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not Found", e.getMessage(), "SG-API-404");
    }

    @ExceptionHandler(WrongTokenException.class)
    ProblemDetail wrongToken(WrongTokenException e) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage(), "SG-API-403");
    }

    @ExceptionHandler(PersistenceUnavailableException.class)
    ProblemDetail unavailable(PersistenceUnavailableException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", e.getMessage(), "SG-API-503");
    }

    @ExceptionHandler(StorageFullException.class)
    ProblemDetail full(StorageFullException e) {
        return problem(HttpStatus.INSUFFICIENT_STORAGE, "Insufficient Storage", e.getMessage(), "SG-API-507");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        // The input is not logged: schemas can be confidential.
        LOG.error("Unexpected error while handling a request", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "Something went wrong on our side.", "SG-API-500");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("about:blank"));
        problem.setProperty("code", code);
        return problem;
    }
}
