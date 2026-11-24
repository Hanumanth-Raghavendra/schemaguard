package io.github.hanumanthraghavendra.schemaguard.api.projects;

/** Answered 404: the project or version does not exist, or belongs to another account (the same answer, so ids reveal nothing). The message is shown to the person. */
public class ProjectNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProjectNotFoundException(String message) {
        super(message);
    }
}
