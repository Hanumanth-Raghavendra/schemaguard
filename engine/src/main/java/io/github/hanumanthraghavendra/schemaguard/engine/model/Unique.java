package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record Unique(
        Optional<String> name,
        List<String> columns,
        List<String> include,
        boolean nullsNotDistinct,
        Deferrability deferrability)
        implements Constraint {

    public Unique {
        Objects.requireNonNull(name, "name");
        columns = List.copyOf(columns);
        include = List.copyOf(include);
        Objects.requireNonNull(deferrability, "deferrability");
    }

    public Unique withDeferrability(Deferrability value) {
        return new Unique(name, columns, include, nullsNotDistinct, value);
    }

    @Override
    public Unique withName(Optional<String> value) {
        return new Unique(value, columns, include, nullsNotDistinct, deferrability);
    }
}
