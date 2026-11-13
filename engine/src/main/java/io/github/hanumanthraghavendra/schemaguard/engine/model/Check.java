package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.Objects;
import java.util.Optional;

/**
 * @param notValid added with {@code NOT VALID}: existing rows are not yet checked
 */
public record Check(Optional<String> name, Expression expression, boolean noInherit, boolean notValid)
        implements Constraint {

    public Check {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(expression, "expression");
    }

    public Check withNotValid(boolean value) {
        return new Check(name, expression, noInherit, value);
    }

    @Override
    public Check withName(Optional<String> value) {
        return new Check(value, expression, noInherit, notValid);
    }

    public Check withExpression(Expression value) {
        return new Check(name, value, noInherit, notValid);
    }
}
