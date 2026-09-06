package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.Socket;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CommitTransactionRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.RollbackTransactionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * Triggers fired by a committed transaction. A DELETED trigger is the interesting case: the commit cannot
 * re-read the document the way it re-reads a saved one, so the document is captured when the delete is
 * buffered and travels with the buffered operation.
 */
public class TransactionTriggerTest {
    private static final Configuration configuration = Configuration.getInstance();

    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final CopyOnWriteArrayList<TriggerEvent> captured = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        IocContainer.get(TriggerExecutor.class).stop();
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.resetClients();
        captured.clear();
        triggerExecutor.stop();
        triggerExecutor.start(captured::add);
        installTriggerFor(EventType.CREATED, EventType.UPDATED, EventType.DELETED);
    }

    private void installTriggerFor(EventType... events) {
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(events)), "recalc",
                        TriggerDefinition.MODE_DOCUMENT, false, true, "owner", 1L, 1L, 1L, "owner")));
    }

    private UUID newClient() {
        final var socket = mock(Socket.class);
        final var address = mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(address);
        when(address.getHostAddress()).thenReturn("127.0.0.1");
        return clientTracker.addClient(socket);
    }

    private static JsonObject document(String id, int value) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.addProperty("value", (long) value);
        return object;
    }

    private void save(String id, int value, UUID clientId) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id, value));
        request.set_id(id);
        processor.processMessage(request, clientId);
    }

    private void delete(String id, UUID clientId) {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        processor.processMessage(request, clientId);
    }

    private List<TriggerEvent> settle() {
        for (int i = 0; i < 100 && captured.isEmpty(); i++) {
            sleep(5);
        }
        sleep(30);
        return List.copyOf(captured);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static double valueOf(TriggerEvent event) {
        return event.getEntries().getFirst().getData().get("value").asJsonNumber().getValue().doubleValue();
    }

    private void bulkSave(UUID clientId) {
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(java.util.Arrays.stream(new String[]{"tx-bulk-old", "tx-bulk-new"})
                .map(id -> document(id, 0)).toList());
        processor.processMessage(request, clientId);
    }

    private static Set<String> idsOf(List<TriggerEvent> events, EventType type) {
        return events.stream().filter(event -> event.getType() == type)
                .map(event -> event.getEntries().getFirst().get_id()).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    public void test_committed_delete_fires_a_deleted_event_carrying_the_document() {
        final var clientId = newClient();
        save("tx-del-1", 7, clientId);
        settle();
        captured.clear();

        processor.processMessage(new StartTransactionRequest(), clientId);
        delete("tx-del-1", clientId);
        processor.processMessage(new CommitTransactionRequest(), clientId);

        final var events = settle();
        assertEquals(1, events.size());
        assertEquals(EventType.DELETED, events.getFirst().getType());
        assertEquals("tx-del-1", events.getFirst().getEntries().getFirst().get_id());
        assertEquals(7d, valueOf(events.getFirst()));
    }

    // The buffered operations replay in order, so a save earlier in the same transaction is the version the
    // delete removes - and the version the trigger must see.
    @Test
    public void test_a_document_saved_then_deleted_in_one_transaction_carries_the_buffered_version() {
        final var clientId = newClient();
        save("tx-del-2", 1, clientId);
        settle();
        captured.clear();

        processor.processMessage(new StartTransactionRequest(), clientId);
        save("tx-del-2", 99, clientId);
        delete("tx-del-2", clientId);
        processor.processMessage(new CommitTransactionRequest(), clientId);

        final var events = settle();
        final var deleted = events.stream().filter(event -> event.getType() == EventType.DELETED).toList();
        assertEquals(1, deleted.size());
        assertEquals(99d, valueOf(deleted.getFirst()));
    }

    @Test
    public void test_a_rolled_back_delete_fires_nothing() {
        final var clientId = newClient();
        save("tx-del-3", 3, clientId);
        settle();
        captured.clear();

        processor.processMessage(new StartTransactionRequest(), clientId);
        delete("tx-del-3", clientId);
        processor.processMessage(new RollbackTransactionRequest(), clientId);

        sleep(80);
        assertTrue(captured.isEmpty());
    }

    @Test
    public void test_nothing_fires_when_no_deleted_trigger_is_installed() {
        final var clientId = newClient();
        save("tx-del-4", 4, clientId);
        settle();
        captured.clear();
        installTriggerFor(EventType.UPDATED);

        processor.processMessage(new StartTransactionRequest(), clientId);
        delete("tx-del-4", clientId);
        processor.processMessage(new CommitTransactionRequest(), clientId);

        sleep(80);
        assertTrue(captured.isEmpty());
    }

    // An insert buffered by a transaction is only distinguishable from an update while it is buffered: by
    // the time the commit fires the trigger, the document exists either way.
    @Test
    public void test_a_document_inserted_in_a_transaction_fires_created() {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        save("tx-new-1", 1, clientId);
        processor.processMessage(new CommitTransactionRequest(), clientId);

        final var events = settle();
        assertEquals(1, events.size());
        assertEquals(EventType.CREATED, events.getFirst().getType());
        assertEquals("tx-new-1", events.getFirst().getEntries().getFirst().get_id());
    }

    @Test
    public void test_a_document_updated_in_a_transaction_fires_updated() {
        final var clientId = newClient();
        save("tx-upd-1", 1, clientId);
        settle();
        captured.clear();

        processor.processMessage(new StartTransactionRequest(), clientId);
        save("tx-upd-1", 2, clientId);
        processor.processMessage(new CommitTransactionRequest(), clientId);

        final var events = settle();
        assertEquals(1, events.size());
        assertEquals(EventType.UPDATED, events.getFirst().getType());
    }

    @Test
    public void test_bulk_save_splits_created_from_updated() {
        final var clientId = newClient();
        save("tx-bulk-old", 1, clientId);
        settle();
        captured.clear();

        processor.processMessage(new StartTransactionRequest(), clientId);
        bulkSave(clientId);
        processor.processMessage(new CommitTransactionRequest(), clientId);

        final var events = settle();
        assertEquals(Set.of("tx-bulk-new"), idsOf(events, EventType.CREATED));
        assertEquals(Set.of("tx-bulk-old"), idsOf(events, EventType.UPDATED));
    }

    // The classification follows the transaction's own read-your-writes view, so the second save of a
    // document the same transaction just created is an update.
    @Test
    public void test_a_document_inserted_then_saved_again_fires_created_then_updated() {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        save("tx-new-2", 1, clientId);
        save("tx-new-2", 2, clientId);
        processor.processMessage(new CommitTransactionRequest(), clientId);

        final var events = settle();
        assertEquals(2, events.size());
        assertEquals(Set.of("tx-new-2"), idsOf(events, EventType.CREATED));
        assertEquals(Set.of("tx-new-2"), idsOf(events, EventType.UPDATED));
    }

    // A delete buffered before the save clears the document, so the save that follows creates it again.
    @Test
    public void test_a_document_deleted_then_saved_again_fires_created() {
        final var clientId = newClient();
        save("tx-new-3", 1, clientId);
        settle();
        captured.clear();

        processor.processMessage(new StartTransactionRequest(), clientId);
        delete("tx-new-3", clientId);
        save("tx-new-3", 2, clientId);
        processor.processMessage(new CommitTransactionRequest(), clientId);

        final var events = settle();
        assertEquals(Set.of("tx-new-3"), idsOf(events, EventType.CREATED));
        assertEquals(Set.of("tx-new-3"), idsOf(events, EventType.DELETED));
    }
}
