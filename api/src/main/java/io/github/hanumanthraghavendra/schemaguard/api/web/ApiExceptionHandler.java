package io.github.hanumanthraghavendra.schemaguard.api.web;

import io.github.hanumanthraghavendra.schemaguard.api.auth.AccountLockedException;
import io.github.hanumanthraghavendra.schemaguard.api.auth.BusyException;
import io.github.hanumanthraghavendra.schemaguard.api.auth.EmailTakenException;
import io.github.hanumanthraghavendra.schemaguard.api.auth.InvalidAccountDetailsException;
import io.github.hanumanthraghavendra.schemaguard.api.auth.LoginFailedException;
import io.github.hanumanthraghavendra.schemaguard.api.auth.RefreshCookie;
import io.github.hanumanthraghavendra.schemaguard.api.auth.SessionEndedException;
import io.github.hanumanthraghavendra.schemaguard.api.auth.WrongPasswordException;
import io.github.hanumanthraghavendra.schemaguard.api.keys.KeyLimitException;
import io.github.hanumanthraghavendra.schemaguard.api.keys.KeyNotFoundException;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.PersistenceUnavailableException;
import io.github.hanumanthraghavendra.schemaguard.api.projects.ProjectConflictException;
import io.github.hanumanthraghavendra.schemaguard.api.projects.ProjectLimitException;
import io.github.hanumanthraghavendra.schemaguard.api.projects.ProjectNotFoundException;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.StorageFullException;
import io.github.hanumanthraghavendra.schemaguard.api.service.AnalysisNotFoundException;
import io.github.hanumanthraghavendra.schemaguard.api.service.InputRejectedException;
import io.github.hanumanthraghavendra.schemaguard.api.service.LimitExceededException;
import io.github.hanumanthraghavendra.schemaguard.api.service.WrongTokenException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(InvalidAccountDetailsException.class)
    ProblemDetail invalidAccount(InvalidAccountDetailsException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Account details cannot be used", e.getMessage(), "SG-API-422");
    }

    @ExceptionHandler(EmailTakenException.class)
    ProblemDetail emailTaken(EmailTakenException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage(), "SG-API-409");
    }

    @ExceptionHandler(LoginFailedException.class)
    ProblemDetail loginFailed(LoginFailedException e) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", e.getMessage(), "SG-API-401");
    }

    /** The session is over, so the browser is told to drop its refresh cookie as well. */
    @ExceptionHandler(SessionEndedException.class)
    ResponseEntity<ProblemDetail> sessionEnded(SessionEndedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, RefreshCookie.clear().toString())
                .body(problem(HttpStatus.UNAUTHORIZED, "Unauthorized", e.getMessage(), "SG-API-401"));
    }

    @ExceptionHandler(WrongPasswordException.class)
    ProblemDetail wrongPassword(WrongPasswordException e) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage(), "SG-API-403");
    }

    @ExceptionHandler(AccountLockedException.class)
    ResponseEntity<ProblemDetail> locked(AccountLockedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .body(problem(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", e.getMessage(), "SG-API-429"));
    }

    @ExceptionHandler(BusyException.class)
    ProblemDetail busy(BusyException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", e.getMessage(), "SG-API-503");
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    ProblemDetail projectNotFound(ProjectNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not Found", e.getMessage(), "SG-API-404");
    }

    @ExceptionHandler(ProjectConflictException.class)
    ProblemDetail projectConflict(ProjectConflictException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage(), "SG-API-409");
    }

    @ExceptionHandler(ProjectLimitException.class)
    ProblemDetail projectLimit(ProjectLimitException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Unprocessable Content", e.getMessage(), "SG-API-422");
    }

    @ExceptionHandler(KeyNotFoundException.class)
    ProblemDetail keyNotFound(KeyNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not Found", e.getMessage(), "SG-API-404");
    }

    @ExceptionHandler(KeyLimitException.class)
    ProblemDetail keyLimit(KeyLimitException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Unprocessable Content", e.getMessage(), "SG-API-422");
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
