package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a rule is, apart from its logic. This is everything a report, the documentation or a
 * user interface needs to show about the rule.
 *
 * @param id       stable identifier such as {@code SG-R101}; never reused for another meaning
 * @param name     short name in kebab case, usable in configuration files
 * @param category the kind of harm
 * @param severity how serious a finding of this rule is
 * @param title    one line that states the problem
 * @param why      why it is a problem, in plain language
 * @param fix      the safer way to reach the same schema
 */
public record RuleInfo(
        String id, String name, Category category, RiskLevel severity, String title, String why, String fix) {

    private static final Pattern ID = Pattern.compile("SG-R[1-9][0-9]{2}");
    private static final Pattern NAME = Pattern.compile("[a-z]+(-[a-z]+)*");

    public RuleInfo {
        Objects.requireNonNull(category, "category");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Rule id must look like SG-R101: " + id);
        }
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Rule name must be kebab case: " + name);
        }
        if (severity == RiskLevel.NONE) {
            throw new IllegalArgumentException("A rule cannot have severity NONE: " + id);
        }
        if (title.isBlank() || why.isBlank() || fix.isBlank()) {
            throw new IllegalArgumentException("Rule " + id + " needs a title, a reason and a fix");
        }
    }
}
