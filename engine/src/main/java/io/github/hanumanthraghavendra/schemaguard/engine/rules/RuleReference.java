package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import java.util.Locale;

/**
 * The rule reference as a Markdown document, written from the rules themselves so that the
 * documentation cannot drift away from the code. {@code docs/RULES.md} is this output, and a
 * test fails when the file is out of date.
 */
public final class RuleReference {

    private RuleReference() {}

    public static String markdown(RuleRegistry registry) {
        StringBuilder out = new StringBuilder();
        out.append("# SchemaGuard rules\n\n");
        out.append("<!-- Generated from the rule classes. Do not edit; run the build with -Dgolden.update=true. -->\n\n");
        out.append(registry.rules().size()).append(" rules. Each one looks at the changes between two schemas and\n");
        out.append("reports what the direct way of applying a change would do to a running database.\n\n");
        out.append("| Id | Severity | Category | Rule |\n|---|---|---|---|\n");
        for (Rule rule : registry.rules()) {
            RuleInfo info = rule.info();
            out.append("| [").append(info.id()).append("](#").append(anchor(info)).append(") | ");
            out.append(info.severity()).append(" | ").append(info.category().label()).append(" | ");
            out.append(info.title()).append(" |\n");
        }
        for (Category category : Category.values()) {
            out.append("\n## ").append(capitalised(category.label())).append("\n\n");
            out.append(category.description()).append('\n');
            for (Rule rule : registry.rules()) {
                RuleInfo info = rule.info();
                if (info.category() == category) {
                    out.append("\n### ").append(info.id()).append(' ').append(info.name()).append("\n\n");
                    out.append("**").append(info.title()).append("** (").append(info.severity()).append(")\n\n");
                    out.append("Why: ").append(info.why()).append("\n\n");
                    out.append("Safer: ").append(info.fix()).append('\n');
                }
            }
        }
        return out.toString();
    }

    /** The anchor GitHub gives the rule's heading. */
    private static String anchor(RuleInfo info) {
        return info.id().toLowerCase(Locale.ROOT) + "-" + info.name();
    }

    private static String capitalised(String text) {
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }
}
