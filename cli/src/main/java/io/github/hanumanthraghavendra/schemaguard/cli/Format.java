package io.github.hanumanthraghavendra.schemaguard.cli;

/** How a command prints its result. */
enum Format {
    /** The engine's plain report, for a terminal. */
    TEXT,
    /** The web app's report layout, for a pull-request comment or a file. */
    MARKDOWN,
    /** The API's response body, for scripts. */
    JSON
}
