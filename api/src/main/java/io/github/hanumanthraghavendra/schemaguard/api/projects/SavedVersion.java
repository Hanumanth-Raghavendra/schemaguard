package io.github.hanumanthraghavendra.schemaguard.api.projects;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A schema version as it is stored, without its SQL.
 *
 * @param number    1 for the first version of a project, then one more each time
 * @param sizeBytes the SQL's size in UTF-8
 * @param risk      the change from the version before, as scored when this one was saved; null for
 *                  the first
 */
public record SavedVersion(UUID projectId, int number, @Nullable String label, int sizeBytes, Instant createdAt, @Nullable Risk risk) {

    /**
     * @param score    0 to 100
     * @param level    NONE, LOW, MEDIUM, HIGH or CRITICAL
     * @param reasons  the findings that count most
     * @param changes  significant changes
     * @param findings risks found
     */
    public record Risk(int score, String level, List<String> reasons, int changes, int findings) {
        public Risk {
            reasons = List.copyOf(reasons);
        }
    }
}
