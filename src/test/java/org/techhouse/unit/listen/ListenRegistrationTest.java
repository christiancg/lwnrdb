package org.techhouse.unit.listen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.techhouse.listen.ListenManager;
import org.techhouse.listen.ListenRegistration;
import org.techhouse.ops.req.AggregateRequest;

public class ListenRegistrationTest {

    @Test
    public void record_fieldsAreAccessible() {
        final var listenId = UUID.randomUUID();
        final var clientId = UUID.randomUUID();
        final var request = new AggregateRequest("db", "coll");
        request.setAggregationSteps(List.of());
        final var keys = Set.of("db|coll");
        final var lastHash = new AtomicReference<>("initialHash");

        final var reg = new ListenRegistration(listenId, clientId, request, keys, lastHash, new AtomicBoolean(true));

        assertEquals(listenId, reg.listenId());
        assertEquals(clientId, reg.clientId());
        assertSame(request, reg.request());
        assertEquals(keys, reg.collectionKeys());
        assertEquals("initialHash", reg.lastHash().get());
        assertTrue(reg.delivered().get());
    }

    @Test
    public void lastHash_canBeUpdated() {
        final var reg = new ListenRegistration(UUID.randomUUID(), UUID.randomUUID(), new AggregateRequest("db", "coll"),
                Set.of("db|coll"), new AtomicReference<>("hash1"), new AtomicBoolean(false));

        reg.lastHash().set("hash2");

        assertEquals("hash2", reg.lastHash().get());
    }

    @Test
    public void register_startsUndelivered() {
        final var manager = new ListenManager();
        final var request = new AggregateRequest("db", "coll");
        request.setAggregationSteps(List.of());

        final var listenId = manager.register(UUID.randomUUID(), request, "hash");

        assertFalse(manager.getRegistration(listenId).delivered().get());
    }
}
