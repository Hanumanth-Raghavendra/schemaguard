package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.List;
import java.util.Objects;

/** An enumerated type. The order of the labels is part of its definition. */
public record EnumType(QualifiedName name, List<String> labels) {

    public EnumType {
        Objects.requireNonNull(name, "name");
        labels = List.copyOf(labels);
    }
}
