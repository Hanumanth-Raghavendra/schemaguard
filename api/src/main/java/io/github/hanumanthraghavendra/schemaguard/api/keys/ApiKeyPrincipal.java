package io.github.hanumanthraghavendra.schemaguard.api.keys;

import java.io.Serializable;
import java.util.UUID;

/** Who a request authenticated with an API key acts for: the key's account. */
public record ApiKeyPrincipal(UUID accountId, UUID keyId, String name) implements Serializable {}
