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

    public Column withName(String value) {
        return new Column(value, type, notNull, defaultValue, identity, generated, collation);
    }

    public Column withType(DataType value) {
        return new Column(name, value, notNull, defaultValue, identity, generated, collation);
    }

    public Column withDefaultValue(Optional<Expression> value) {
        return new Column(name, type, notNull, value, identity, generated, collation);
    }

    public Column withIdentity(Identity value) {
        return new Column(name, type, notNull, defaultValue, value, generated, collation);
    }

    public Column withGenerated(Optional<GeneratedColumn> value) {
        return new Column(name, type, notNull, defaultValue, identity, value, collation);
    }

    public Column withCollation(Optional<String> value) {
        return new Column(name, type, notNull, defaultValue, identity, generated, value);
    }
}
