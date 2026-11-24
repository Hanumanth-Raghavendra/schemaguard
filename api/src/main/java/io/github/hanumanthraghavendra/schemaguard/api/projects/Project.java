package io.github.hanumanthraghavendra.schemaguard.api.projects;

import java.time.Instant;
import java.util.UUID;

/** A project as it is stored: a named schema of one account. */
public record Project(UUID id, UUID accountId, String name, Instant createdAt) {}
