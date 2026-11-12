package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.Objects;
import java.util.Optional;

/** One column of a table. */
public record Column(
        String name,
        DataType type,
        boolean notNull,
        Optional<Expression> defaultValue,
        Identity identity,
        Optional<GeneratedColumn> generated,
        Optional<String> collation) {

    public Column {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(generated, "generated");
        Objects.requireNonNull(collation, "collation");
    }

    public Column withNotNull(boolean value) {
        return new Column(name, type, value, defaultValue, identity, generated, collation);
    }
}
