package io.github.hanumanthraghavendra.schemaguard.api.keys;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An API key as it is stored: never the key itself.
 *
 * @param hint       the key's first characters ({@code sg_AbCd}), to tell keys apart
 * @param lastUsedAt when a request last used it, to the minute; null if never
 */
public record ApiKey(UUID id, UUID accountId, String name, String hint, Instant createdAt, @Nullable Instant lastUsedAt) {}
