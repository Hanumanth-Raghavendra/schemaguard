package io.github.hanumanthraghavendra.schemaguard.engine.rules;

/**
 * How serious a risk is. Rules use {@link #LOW} to {@link #CRITICAL} as their severity; a
 * whole report can also be {@link #NONE}.
 *
 * <ul>
 *   <li>{@code CRITICAL}: data is destroyed and cannot be brought back without a backup.
 *   <li>{@code HIGH}: reads or writes are blocked for a time that grows with the table, the
 *       migration can fail half way, or the running application breaks.
 *   <li>{@code MEDIUM}: a real effect that is brief, partial or depends on how the
 *       application uses the schema.
 *   <li>{@code LOW}: worth knowing; rarely a problem on its own.
 * </ul>
 */
public enum RiskLevel {
    NONE(0),
    LOW(10),
    MEDIUM(30),
    HIGH(60),
    CRITICAL(85);

    private final int weight;

    RiskLevel(int weight) {
        this.weight = weight;
    }

    /** The score, out of 100, of one finding of this severity that is certain to apply. */
    public int weight() {
        return weight;
    }

    /** The level a score from 0 to 100 stands for. Each weight lies inside its own band. */
    public static RiskLevel ofScore(int score) {
        if (score >= 75) {
            return CRITICAL;
        }
        if (score >= 50) {
            return HIGH;
        }
        if (score >= 25) {
            return MEDIUM;
        }
        return score >= 1 ? LOW : NONE;
    }
}
