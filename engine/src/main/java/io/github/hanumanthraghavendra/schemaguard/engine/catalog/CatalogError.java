package io.github.hanumanthraghavendra.schemaguard.engine.catalog;

/**
 * Internal control flow: thrown when a statement cannot be applied and caught once per
 * statement, where it becomes a diagnostic. It never leaves this package. The messages follow
 * PostgreSQL's own wording so they are familiar and searchable.
 */
final class CatalogError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    private CatalogError(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    String code() {
        return code;
    }

    static CatalogError duplicateObject(String message) {
        return new CatalogError(CatalogErrors.DUPLICATE_OBJECT, message);
    }

    static CatalogError undefinedObject(String message) {
        return new CatalogError(CatalogErrors.UNDEFINED_OBJECT, message);
    }

    static CatalogError undefinedColumn(String message) {
        return new CatalogError(CatalogErrors.UNDEFINED_COLUMN, message);
    }

    static CatalogError duplicateColumn(String message) {
        return new CatalogError(CatalogErrors.DUPLICATE_COLUMN, message);
    }

    static CatalogError dependentObjects(String message) {
        return new CatalogError(CatalogErrors.DEPENDENT_OBJECTS, message);
    }

    static CatalogError invalid(String message) {
        return new CatalogError(CatalogErrors.INVALID_DEFINITION, message);
    }
}
