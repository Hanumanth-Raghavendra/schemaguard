package io.github.hanumanthraghavendra.schemaguard.engine.rules;

import java.util.Comparator;
import java.util.List;

/**
 * The risk of a whole migration as one number from 0 to 100, and what produced it.
 *
 * <p>The findings are combined the way independent chances of trouble combine: each finding
 * leaves a share of "nothing went wrong", and the shares multiply.
 *
 * <pre>score = 100 x (1 - (1 - s1/100) x (1 - s2/100) x ...)</pre>
 *
 * <p>where s1, s2, ... are the scores of the findings. This has the properties a risk score
 * needs: one finding alone gives its own score; every further finding raises the total;
 * many small risks add up to a large one; and the total never passes 100.
 *
 * @param value   0 for no findings, up to 100
 * @param level   the band the value falls in
 * @param reasons the findings that count most, strongest first
 */
public record RiskScore(int value, RiskLevel level, List<String> reasons) {

    /** How many findings are named as reasons. */
    static final int MAX_REASONS = 5;

    public RiskScore {
        reasons = List.copyOf(reasons);
    }

    public static RiskScore of(List<? extends Scored> findings) {
        double nothingWentWrong = 1.0;
        for (Scored finding : findings) {
            nothingWentWrong *= 1.0 - finding.score() / 100.0;
        }
        int value = (int) Math.round(100 * (1 - nothingWentWrong));
        List<String> reasons =
                findings.stream()
                        .sorted(Comparator.comparingInt((Scored finding) -> finding.score()).reversed())
                        .limit(MAX_REASONS)
                        .map(finding -> finding.score() + " " + finding.rule().id() + " " + finding.rule().title())
                        .toList();
        return new RiskScore(value, RiskLevel.ofScore(value), reasons);
    }
}
