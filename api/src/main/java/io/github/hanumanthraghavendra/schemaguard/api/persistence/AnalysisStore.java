package io.github.hanumanthraghavendra.schemaguard.api.persistence;

import java.time.Instant;
import java.util.Optional;

/** Where saved analyses are kept. */
public interface AnalysisStore {

    /**
     * A saved analysis.
     *
     * @param request       the request as JSON, as it was sent
     * @param result        the result as JSON, as it was returned
     * @param engineVersion the version of SchemaGuard that produced the result
     */
    record Saved(
            String id,
            String kind,
            String request,
            String result,
            String engineVersion,
            Instant createdAt,
            Instant expiresAt) {}

    /** What deleting with a token found. */
    enum Deletion {
        DELETED,
        NOT_FOUND,
        WRONG_TOKEN
    }

    /**
     * Saves an analysis.
     *
     * @param tokenHash the SHA-256 hash of the delete token
     * @throws StorageFullException if the analyses already saved take all the room there is
     */
    void save(Saved analysis, String tokenHash);

    /** The analysis with this id, unless it does not exist or has expired. */
    Optional<Saved> find(String id);

    Deletion delete(String id, String tokenHash);

    /** Removes every analysis that has expired; returns how many. */
    int deleteExpired();
}
