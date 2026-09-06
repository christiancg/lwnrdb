package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.config.Configuration;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OperationProcessorTriggerTest {
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
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

    private static JsonObject document(String id, int value) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.addProperty("value", (long) value);
        return object;
    }

    private void save(String id, int value) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id, value));
        request.set_id(id);
        processor.processMessage(request);
    }

    @Test
    public void test_save_submits_an_updated_event() {
        save("s1", 1);
        final var events = settle();
        assertEquals(1, events.size());
        assertEquals(EventType.UPDATED, events.getFirst().getType());
        assertEquals("s1", events.getFirst().getEntries().getFirst().get_id());
        assertEquals("audit", events.getFirst().getTriggerName());
    }

    @Test
    public void test_insert_without_an_id_submits_a_created_event() {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.addProperty("value", 7L);
        request.setObject(object);
        processor.processMessage(request);
        final var events = settle();
        assertEquals(1, events.size());
        assertEquals(EventType.CREATED, events.getFirst().getType());
    }

    @Test
    public void test_bulk_save_submits_one_event_per_document() {
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(document("b1", 1), document("b2", 2)));
        processor.processMessage(request);
        final var events = settle();
        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(event -> event.getEntries().size() == 1));
    }

    @Test
    public void test_delete_submits_a_deleted_event_carrying_the_document() {
        save("d1", 5);
        settle();
        captured.clear();
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("d1");
        processor.processMessage(request);
        final var events = settle();
        assertEquals(1, events.size());
        assertEquals(EventType.DELETED, events.getFirst().getType());
        assertEquals("d1", events.getFirst().getEntries().getFirst().get_id());
        assertEquals(5d, events.getFirst().getEntries().getFirst().getData().get("value").asJsonNumber().getValue()
                .doubleValue());
    }

    // The correctness property the seam placement exists for: ReplicatedApplyHelper reaches the write
    // helpers directly, bypassing OperationProcessor, so a replica must not re-fire a trigger the owner
    // has already fired.
    @Test
    public void test_replicated_apply_does_not_fire_triggers() {
        assertTrue(ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                ReplicationOp.UPSERT, List.of(document("r1", 1)), List.of("r1"))));
        sleep(80);
        assertTrue(captured.isEmpty(), "a replicated apply must not fire a trigger");
    }

    @Test
    public void test_replicated_delete_does_not_fire_triggers() {
        save("r2", 1);
        settle();
        captured.clear();
        assertTrue(ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                ReplicationOp.DELETE, List.of(), List.of("r2"))));
        sleep(80);
        assertTrue(captured.isEmpty(), "a replicated delete must not fire a trigger");
    }

    @Test
    public void test_fires_nothing_when_disabled() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        save("off", 1);
        sleep(80);
        assertTrue(captured.isEmpty());
    }

    @Test
    public void test_only_the_matching_event_fires() {
        installTriggerFor(EventType.DELETED);
        save("only-delete", 1);
        sleep(80);
        assertTrue(captured.isEmpty());
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("only-delete");
        processor.processMessage(request);
        assertEquals(1, settle().size());
    }

    // A client can never claim a cascade depth; only a running trigger's writes carry one
    @Test
    public void test_a_client_originated_write_is_depth_zero() {
        save("depth", 1);
        assertEquals(0, settle().getFirst().getDepth());
    }
}
