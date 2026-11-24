package io.github.hanumanthraghavendra.schemaguard.api.projects;

/** Answered 409: the name is taken, the version is identical to the latest, or only the latest version may be deleted. The message is shown to the person. */
public class ProjectConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProjectConflictException(String message) {
        super(message);
    }
}
