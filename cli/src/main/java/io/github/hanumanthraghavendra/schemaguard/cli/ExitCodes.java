package io.github.hanumanthraghavendra.schemaguard.cli;

/** What the process's exit code means; CI steps branch on these. */
final class ExitCodes {

    /** Done, and the risk is within {@code --fail-above} if one was given. */
    static final int OK = 0;
    /** The risk is above {@code --fail-above}, or a checked migration has statements PostgreSQL would refuse. */
    static final int RISK_ABOVE_LIMIT = 1;
    /** A usage error, or input that cannot be analysed. */
    static final int BAD_INPUT = 2;
    /** The SchemaGuard API refused the request or could not be reached ({@code save} only). */
    static final int API_FAILED = 3;
    /** A bug in SchemaGuard. */
    static final int INTERNAL = 4;

    private ExitCodes() {}
}
