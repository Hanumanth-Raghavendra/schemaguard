package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record PrimaryKey(
        Optional<String> name, List<String> columns, List<String> include, Deferrability deferrability)
        implements Constraint {

    public PrimaryKey {
        Objects.requireNonNull(name, "name");
        columns = List.copyOf(columns);
        include = List.copyOf(include);
        Objects.requireNonNull(deferrability, "deferrability");
    }

    public PrimaryKey withDeferrability(Deferrability value) {
        return new PrimaryKey(name, columns, include, value);
    }

    @Override
    public PrimaryKey withName(Optional<String> value) {
        return new PrimaryKey(value, columns, include, deferrability);
    }
}
