package org.techhouse.listen;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.techhouse.ops.req.AggregateRequest;

public record ListenRegistration(UUID listenId, UUID clientId, AggregateRequest request, Set<String> collectionKeys,
        AtomicReference<String> lastHash) {
}
