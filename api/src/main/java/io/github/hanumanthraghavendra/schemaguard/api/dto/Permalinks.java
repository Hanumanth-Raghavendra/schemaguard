package io.github.hanumanthraghavendra.schemaguard.api.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Saved analyses: what is returned when one is saved and when one is opened. */
public final class Permalinks {

    private Permalinks() {}

    @Schema(name = "SavedAnalysis", description = "An analysis that was just saved")
    public record Saved(
            @Schema(description = "Opens the analysis at GET /api/v1/analyses/{id}", example = "3kT9xQ0bV2mZp8LcR4nW1a")
                    String id,
            @Schema(example = "report") String kind,
            @Schema(description = "Path of the saved analysis", example = "/api/v1/analyses/3kT9xQ0bV2mZp8LcR4nW1a") String path,
            Instant createdAt,
            @Schema(description = "After this moment the analysis is deleted") Instant expiresAt,
            @Schema(description = "Needed to delete the analysis before it expires. Shown only now; it cannot be recovered")
                    String deleteToken,
            @Schema(types = {"object"}, description = "The result, as the matching analysis endpoint returns it")
                    @JsonRawValue
                    String result) {}

    @Schema(name = "StoredAnalysis", description = "A saved analysis, as it was computed")
    public record Stored(
            String id,
            @Schema(description = "diff, report, plan or lint") String kind,
            Instant createdAt,
            Instant expiresAt,
            @Schema(description = "The SchemaGuard version that computed the result") String engineVersion,
            @Schema(types = {"object"}, description = "The request, as it was sent") @JsonRawValue String request,
            @Schema(types = {"object"}, description = "The result, as it was returned") @JsonRawValue String result) {}
}
