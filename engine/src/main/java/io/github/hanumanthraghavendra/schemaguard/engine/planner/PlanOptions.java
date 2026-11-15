package io.github.hanumanthraghavendra.schemaguard.engine.planner;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * How a plan is to be made.
 *
 * @param route       the direct route or the safe one
 * @param renames     renames a person has confirmed, applied in this order
 * @param lockTimeout the value for {@code SET lock_timeout} at the top of a safe plan
 */
public record PlanOptions(Route route, List<Rename> renames, String lockTimeout) {

    /** Which statements a plan is made of. */
    public enum Route {
        /** One plain statement per change: the migration most people write first. */
        DIRECT,
        /**
         * The same result with statements that do not block the application for long:
         * indexes built concurrently, constraints added unvalidated and validated afterwards.
         */
        SAFE
    }

    public static final String DEFAULT_LOCK_TIMEOUT = "5s";

    public PlanOptions {
        Objects.requireNonNull(route, "route");
        renames = List.copyOf(new LinkedHashSet<>(renames));
        if (!lockTimeout.matches("[0-9]+(ms|s|min)?")) {
            throw new IllegalArgumentException("Not a lock timeout: " + lockTimeout);
        }
    }

    public static PlanOptions direct() {
        return new PlanOptions(Route.DIRECT, List.of(), DEFAULT_LOCK_TIMEOUT);
    }

    public static PlanOptions safe() {
        return new PlanOptions(Route.SAFE, List.of(), DEFAULT_LOCK_TIMEOUT);
    }

    public PlanOptions withRenames(List<Rename> confirmed) {
        return new PlanOptions(route, confirmed, lockTimeout);
    }

    public PlanOptions withRenames(Set<Rename> confirmed) {
        return new PlanOptions(route, List.copyOf(confirmed), lockTimeout);
    }

    public PlanOptions withLockTimeout(String value) {
        return new PlanOptions(route, renames, value);
    }
}
