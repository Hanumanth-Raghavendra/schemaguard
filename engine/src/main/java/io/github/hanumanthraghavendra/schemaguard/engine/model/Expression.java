package io.github.hanumanthraghavendra.schemaguard.engine.model;

import java.util.Objects;

/**
 * An SQL expression (a default value, a check condition) held as canonical text.
 *
 * <p>The engine does not evaluate expressions. It only needs to know whether two are the same
 * and to print them, so the parser normalises spacing, case and quoting and stores the result.
 */
public record Expression(String sql) {

    public Expression {
        Objects.requireNonNull(sql, "sql");
        if (sql.isBlank()) {
            throw new IllegalArgumentException("An expression cannot be blank");
        }
    }

    @Override
    public String toString() {
        return sql;
    }
}
