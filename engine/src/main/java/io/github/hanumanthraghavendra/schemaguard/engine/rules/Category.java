package io.github.hanumanthraghavendra.schemaguard.engine.rules;

/** The kind of harm a rule warns about. */
public enum Category {
    LOCKING("locking", "The change holds a lock that makes the application's queries wait."),
    REWRITE("table rewrite", "The change writes the whole table again while everything else is locked out."),
    DATA_LOSS("data loss", "The change destroys data, or would if applied the direct way."),
    FAILURE("can fail", "The change is rejected by PostgreSQL when the existing rows do not allow it."),
    COMPATIBILITY("compatibility", "The change can break an application that still expects the old schema.");

    private final String label;
    private final String description;

    Category(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
