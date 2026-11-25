package io.github.hanumanthraghavendra.schemaguard.cli;

/** Input the CLI cannot work with: a missing file, SQL with errors, a bad folder. Exit code 2. */
final class InputException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InputException(String message) {
        super(message);
    }
}
