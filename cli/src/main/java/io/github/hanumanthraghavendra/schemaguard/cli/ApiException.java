package io.github.hanumanthraghavendra.schemaguard.cli;

/** The SchemaGuard API refused a request or could not be reached. Exit code 3. */
final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ApiException(String message) {
        super(message);
    }
}
