package io.github.hanumanthraghavendra.schemaguard.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** API keys: what the key endpoints return. */
public final class Keys {

    private Keys() {}

    @Schema(name = "ApiKey", description = "An API key, without its value")
    public record ApiKey(
            UUID id,
            String name,
            @Schema(description = "The key's first characters, to tell keys apart", example = "sg_Ab3d") String hint,
            Instant createdAt,
            @Schema(description = "When a request last used it, to the minute; null if never") @Nullable Instant lastUsedAt) {}

    @Schema(name = "NewApiKey", description = "A key just made, with its whole value: shown this once, never again")
    public record NewApiKey(
            UUID id,
            String name,
            String hint,
            Instant createdAt,
            @Schema(description = "Send as Authorization: Bearer <key>. Keep it secret, like a password") String key) {

        @Override
        public String toString() {
            return "NewApiKey[" + id + "]";
        }
    }
}
