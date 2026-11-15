package io.github.hanumanthraghavendra.schemaguard.engine.rules;

/** Something a rule found that counts towards a {@link RiskScore}. */
public interface Scored {

    RuleInfo rule();

    /** From 1 to 100. */
    int score();
}
