package io.github.hanumanthraghavendra.schemaguard.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.api.service.InputRejectedException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

/** The one error no request can cause on purpose: a bug. Its message must not reach the client. */
class ApiExceptionHandlerTest {

    @Test
    void anUnexpectedErrorHidesItsDetails() {
        ProblemDetail problem = new ApiExceptionHandler().unexpected(new IllegalStateException("CREATE TABLE secret (...)"));
        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getDetail()).isEqualTo("Something went wrong on our side.");
        assertThat(problem.getProperties()).containsEntry("code", "SG-API-500");
        assertThat(problem.toString()).doesNotContain("secret");
    }

    @Test
    void aRejectionWithoutDiagnosticsStillHasTheField() {
        ProblemDetail problem = new ApiExceptionHandler().rejected(new InputRejectedException("Bad rename", List.of()));
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getProperties()).containsEntry("diagnostics", List.of());
    }
}
