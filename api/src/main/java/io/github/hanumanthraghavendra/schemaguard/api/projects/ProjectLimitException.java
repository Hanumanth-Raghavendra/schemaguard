package io.github.hanumanthraghavendra.schemaguard.api.projects;

/** Answered 422: the account has reached a limit (projects, versions or stored schema text), or a name or label cannot be used. The message is shown to the person. */
public class ProjectLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProjectLimitException(String message) {
        super(message);
    }
}
