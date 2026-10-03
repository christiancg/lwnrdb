package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.ops.DeleteOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReplicatedApplyHelperTest {
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        return object;
    }

    private OperationStatus findStatus(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    @Test
    public void test_apply_upsert_stores_documents() {
        final var payload = new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("r1"), doc("r2")), null);
        assertTrue(ReplicatedApplyHelper.apply(payload));
        assertEquals(OperationStatus.OK, findStatus("r1"));
        assertEquals(OperationStatus.OK, findStatus("r2"));
    }

    @Test
    public void test_apply_delete_removes_document() {
        ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("r3")), null));
        assertEquals(OperationStatus.OK, findStatus("r3"));
        assertTrue(ReplicatedApplyHelper.apply(
                new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.DELETE, null, List.of("r3"))));
        assertEquals(OperationStatus.NOT_FOUND, findStatus("r3"));
    }

    @Test
    public void test_apply_delete_of_missing_id_is_idempotent() {
        assertTrue(ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                ReplicationOp.DELETE, null, List.of("does-not-exist"))));
    }

    @Test
    public void test_apply_null_or_opless_payload_returns_false() {
        assertFalse(ReplicatedApplyHelper.apply(null));
        assertFalse(ReplicatedApplyHelper.apply(new ReplicationPayload()));
    }

    private static int dirtyQueueSize() throws Exception {
        return ((LinkedBlockingQueue<?>) TestUtils.getPrivateField(listenManager, "dirtyQueue",
                LinkedBlockingQueue.class)).size();
    }

    private static UUID registerListener() {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of());
        request.setDirtyRead(true);
        return listenManager.register(UUID.randomUUID(), request, "hash");
    }

    // The dirty queue collapses repeated marks for one listener, so queue size cannot tell a deferred
    // batch from an eager one. What distinguishes them is whether a mark taken mid-apply is queued at
    // all, which is what the write helper does for every id of the payload.
    @Test
    public void test_a_multi_id_replicated_delete_defers_every_mark_until_the_batch_ends() throws Exception {
        final var listenId = registerListener();
        final var baseline = dirtyQueueSize();
        final var queuedDuringApply = new ArrayList<Integer>();
        try (var mocked = mockStatic(DeleteOperationHelper.class)) {
            mocked.when(() -> DeleteOperationHelper.executeDelete(any())).thenAnswer(ignored -> {
                listenManager.markDirty(TestGlobals.DB, TestGlobals.COLL);
                queuedDuringApply.add(dirtyQueueSize() - baseline);
                return null;
            });
            assertTrue(ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                    ReplicationOp.DELETE, null, List.of("n1", "n2", "n3"))));
        } finally {
            listenManager.unregister(listenId);
        }
        assertEquals(List.of(0, 0, 0), queuedDuringApply,
                "a listener must not be woken while the payload is only part applied");
        assertEquals(1, dirtyQueueSize() - baseline);
    }

    @Test
    public void test_a_replicated_upsert_batch_notifies_listeners_once() throws Exception {
        final var listenId = registerListener();
        try {
            final var before = dirtyQueueSize();
            assertTrue(ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                    ReplicationOp.UPSERT, List.of(doc("u1"), doc("u2"), doc("u3")), null)));
            assertEquals(1, dirtyQueueSize() - before);
        } finally {
            listenManager.unregister(listenId);
        }
    }
    @Test
    public void test_the_flush_runs_even_when_an_apply_throws() throws Exception {
        final var listenId = registerListener();
        try (var mocked = mockStatic(DeleteOperationHelper.class)) {
            mocked.when(() -> DeleteOperationHelper.executeDelete(any())).thenAnswer(ignored -> {
                listenManager.markDirty(TestGlobals.DB, TestGlobals.COLL);
                throw new java.io.IOException("disk full");
            });
            final var before = dirtyQueueSize();
            assertFalse(ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                    ReplicationOp.DELETE, null, List.of("f1"))));
            assertEquals(1, dirtyQueueSize() - before,
                    "a failed apply must not leave the deferral armed on this thread");
        } finally {
            listenManager.unregister(listenId);
        }
    }
}
