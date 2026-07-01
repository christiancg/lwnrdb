package org.techhouse.unit.listen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.listen.ListenManager;
import org.techhouse.ops.req.AggregateRequest;

public class ListenManagerTest {

    private ListenManager manager;

    @BeforeEach
    void setUp() {
        manager = new ListenManager();
    }

    private AggregateRequest dirtyRequest(String db, String coll) {
        final var req = new AggregateRequest(db, coll);
        req.setAggregationSteps(List.of());
        req.setDirtyRead(true);
        return req;
    }

    @Test
    public void register_returnsNonNullListenId() {
        final var listenId = manager.register(UUID.randomUUID(), dirtyRequest("db", "coll"), "hash");

        assertNotNull(listenId);
    }

    @Test
    public void register_registrationIsRetrievable() {
        final var clientId = UUID.randomUUID();
        final var listenId = manager.register(clientId, dirtyRequest("db", "coll"), "hash");

        final var reg = manager.getRegistration(listenId);

        assertNotNull(reg);
        assertEquals(listenId, reg.listenId());
        assertEquals(clientId, reg.clientId());
        assertEquals("hash", reg.lastHash().get());
    }

    @Test
    public void unregister_existingListen_returnsTrue() {
        final var listenId = manager.register(UUID.randomUUID(), dirtyRequest("db", "coll"), "hash");

        assertTrue(manager.unregister(listenId));
    }

    @Test
    public void unregister_nonExistentListen_returnsFalse() {
        assertFalse(manager.unregister(UUID.randomUUID()));
    }

    @Test
    public void unregister_registrationIsGone() {
        final var listenId = manager.register(UUID.randomUUID(), dirtyRequest("db", "coll"), "hash");
        manager.unregister(listenId);

        assertNull(manager.getRegistration(listenId));
    }

    @Test
    public void unregisterAllForClient_removesOnlyThatClientsRegistrations() {
        final var clientA = UUID.randomUUID();
        final var clientB = UUID.randomUUID();
        final var idA1 = manager.register(clientA, dirtyRequest("db", "coll"), "hash");
        final var idA2 = manager.register(clientA, dirtyRequest("db", "other"), "hash");
        final var idB = manager.register(clientB, dirtyRequest("db", "coll"), "hash");

        manager.unregisterAllForClient(clientA);

        assertNull(manager.getRegistration(idA1));
        assertNull(manager.getRegistration(idA2));
        assertNotNull(manager.getRegistration(idB));
    }

    @Test
    public void unregisterAllForCollection_removesOnlyThatCollectionsRegistrations() {
        final var clientA = UUID.randomUUID();
        final var clientB = UUID.randomUUID();
        final var idA = manager.register(clientA, dirtyRequest("db", "coll"), "hash");
        final var idB = manager.register(clientB, dirtyRequest("db", "other"), "hash");

        manager.unregisterAllForCollection("db", "coll");

        assertNull(manager.getRegistration(idA));
        assertNotNull(manager.getRegistration(idB));
    }

    @Test
    public void unregisterAllForCollection_emptyCollection_doesNotThrow() {
        assertDoesNotThrow(() -> manager.unregisterAllForCollection("db", "nonexistent"));
    }

    @Test
    public void unregisterAllForDatabase_removesAllCollectionsInDatabase() {
        final var client = UUID.randomUUID();
        final var idA = manager.register(client, dirtyRequest("mydb", "coll1"), "hash");
        final var idB = manager.register(client, dirtyRequest("mydb", "coll2"), "hash");
        final var idC = manager.register(client, dirtyRequest("other", "coll1"), "hash");

        manager.unregisterAllForDatabase("mydb");

        assertNull(manager.getRegistration(idA));
        assertNull(manager.getRegistration(idB));
        assertNotNull(manager.getRegistration(idC));
    }

    @Test
    public void unregisterAllForDatabase_emptyDatabase_doesNotThrow() {
        assertDoesNotThrow(() -> manager.unregisterAllForDatabase("nonexistent"));
    }

    @Test
    public void markDirty_noListens_doesNotThrow() {
        assertDoesNotThrow(() -> manager.markDirty("db", "coll"));
    }

    @Test
    public void startAndStopWorkers_doesNotThrow() {
        assertDoesNotThrow(() -> {
            manager.startWorkers();
            manager.stopWorkers();
        });
    }

    @Test
    public void getRegistration_nullAfterStopWorkers() {
        final var listenId = manager.register(UUID.randomUUID(), dirtyRequest("db", "coll"), "hash");
        manager.stopWorkers();

        assertNull(manager.getRegistration(listenId));
    }
}
