package io.github.hanumanthraghavendra.schemaguard.engine.catalog;

/** Stable diagnostic codes reported while building a {@link io.github.hanumanthraghavendra.schemaguard.engine.model.Catalog}. */
public final class CatalogErrors {

    /** An object of that name already exists. */
    public static final String DUPLICATE_OBJECT = "SG-CAT-001";

    /** A table, index, type, sequence or constraint that the statement names does not exist. */
    public static final String UNDEFINED_OBJECT = "SG-CAT-002";

    public static final String UNDEFINED_COLUMN = "SG-CAT-003";

    public static final String DUPLICATE_COLUMN = "SG-CAT-004";

    /** The object cannot be dropped because something else still depends on it. */
    public static final String DEPENDENT_OBJECTS = "SG-CAT-005";

    /** The statement asks for something PostgreSQL does not allow, such as two primary keys. */
    public static final String INVALID_DEFINITION = "SG-CAT-006";

    private CatalogErrors() {}
}
