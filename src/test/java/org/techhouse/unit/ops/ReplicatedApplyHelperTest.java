package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.DirtySink;
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
        listenManager.stopWorkers();
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
        assertTrue(ReplicatedApplyHelper.apply(payload, TestGlobals.APPLY_BUDGET_MS));
        assertEquals(OperationStatus.OK, findStatus("r1"));
        assertEquals(OperationStatus.OK, findStatus("r2"));
    }

    @Test
    public void test_apply_delete_removes_document() {
        ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("r3")), null), TestGlobals.APPLY_BUDGET_MS);
        assertEquals(OperationStatus.OK, findStatus("r3"));
        assertTrue(ReplicatedApplyHelper.apply(
                new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.DELETE, null, List.of("r3")),
                TestGlobals.APPLY_BUDGET_MS));
        assertEquals(OperationStatus.NOT_FOUND, findStatus("r3"));
    }

    @Test
    public void test_apply_delete_of_missing_id_is_idempotent() {
        assertTrue(ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                ReplicationOp.DELETE, null, List.of("does-not-exist")), TestGlobals.APPLY_BUDGET_MS));
    }

    @Test
    public void test_apply_null_or_opless_payload_returns_false() {
        assertFalse(ReplicatedApplyHelper.apply(null, TestGlobals.APPLY_BUDGET_MS));
        assertFalse(ReplicatedApplyHelper.apply(new ReplicationPayload(), TestGlobals.APPLY_BUDGET_MS));
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
            mocked.when(() -> DeleteOperationHelper.executeBulkDelete(any(), any(), any(), any(), anyBoolean()))
                    .thenAnswer(invocation -> {
                        final List<String> ids = invocation.getArgument(2);
                        for (final var ignored : ids) {
                            invocation.getArgument(3, DirtySink.class).markDirty(TestGlobals.DB, TestGlobals.COLL);
                            queuedDuringApply.add(dirtyQueueSize() - baseline);
                        }
                        return ids;
                    });
            assertTrue(ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                    ReplicationOp.DELETE, null, List.of("n1", "n2", "n3")), TestGlobals.APPLY_BUDGET_MS));
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
                    ReplicationOp.UPSERT, List.of(doc("u1"), doc("u2"), doc("u3")), null),
                    TestGlobals.APPLY_BUDGET_MS));
            assertEquals(1, dirtyQueueSize() - before);
        } finally {
            listenManager.unregister(listenId);
        }
    }
    @Test
    public void test_the_flush_runs_even_when_an_apply_throws() throws Exception {
        final var listenId = registerListener();
        try (var mocked = mockStatic(DeleteOperationHelper.class)) {
            mocked.when(() -> DeleteOperationHelper.executeBulkDelete(any(), any(), any(), any(), anyBoolean()))
                    .thenAnswer(invocation -> {
                        invocation.getArgument(3, DirtySink.class).markDirty(TestGlobals.DB, TestGlobals.COLL);
                        throw new java.io.IOException("disk full");
                    });
            final var before = dirtyQueueSize();
            assertFalse(ReplicatedApplyHelper.apply(
                    new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.DELETE, null, List.of("f1")),
                    TestGlobals.APPLY_BUDGET_MS));
            assertEquals(1, dirtyQueueSize() - before,
                    "a failed apply must not leave the deferral armed on this thread");
        } finally {
            listenManager.unregister(listenId);
        }
    }

    @Test
    public void test_an_interrupted_apply_applies_nothing_and_keeps_the_interrupt() {
        final var payload = new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("interrupted")), null, List.of("1"));
        Thread.currentThread().interrupt();
        final var applied = ReplicatedApplyHelper.apply(payload, 1000L);
        final var stillInterrupted = Thread.interrupted();

        assertFalse(applied);
        assertTrue(stillInterrupted,
                "a shutdown interrupt must survive the apply so the sweep that owns this thread stops");
        assertEquals(OperationStatus.NOT_FOUND, findStatus("interrupted"));
    }

    private static List<String> tombstoneLines() throws Exception {
        final var file = java.nio.file.Path.of(TestGlobals.PATH, TestGlobals.DB, TestGlobals.COLL,
                TestGlobals.COLL + "-tombstones.idx");
        return java.nio.file.Files.exists(file) ? java.nio.file.Files.readAllLines(file) : List.of();
    }

    private static boolean cached(String id) {
        final var cachedCollection = IocContainer.get(Cache.class).userCache().getCachedCollection(TestGlobals.DB,
                TestGlobals.COLL);
        return cachedCollection != null && cachedCollection.containsKey(id);
    }

    private static boolean applyServing(boolean owns, ReplicationPayload payload) throws Exception {
        final var original = TestUtils.getPrivateStaticField(ReplicatedApplyHelper.class, "coordinator",
                ClusterCoordinator.class);
        final var coordinator = mock(ClusterCoordinator.class);
        when(coordinator.stillOwns(any(), any())).thenReturn(owns);
        TestUtils.setPrivateStaticField(ReplicatedApplyHelper.class, "coordinator", coordinator);
        try {
            return ReplicatedApplyHelper.apply(payload, TestGlobals.APPLY_BUDGET_MS);
        } finally {
            TestUtils.setPrivateStaticField(ReplicatedApplyHelper.class, "coordinator", original);
        }
    }

    @Test
    public void test_a_delete_payload_appends_tombstones_once() throws Exception {
        final var before = tombstoneLines().size();

        assertTrue(ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                ReplicationOp.DELETE, null, List.of("ta", "tb", "tc"), List.of("10", "11", "12")),
                TestGlobals.APPLY_BUDGET_MS));

        assertEquals(before + 3, tombstoneLines().size());
        final var tombstones = IocContainer.get(FileSystem.class).tombstones().view(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(12L, tombstones.versionOf("tc"));
    }

    @Test
    public void test_a_known_tombstone_is_not_reappended() throws Exception {
        final var payload = new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.DELETE, null,
                List.of("known"), List.of("20"));
        assertTrue(ReplicatedApplyHelper.apply(payload, TestGlobals.APPLY_BUDGET_MS));
        final var after = tombstoneLines().size();

        assertTrue(ReplicatedApplyHelper.apply(payload, TestGlobals.APPLY_BUDGET_MS));
        assertTrue(ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                ReplicationOp.DELETE, null, List.of("known"), List.of("19")), TestGlobals.APPLY_BUDGET_MS));

        assertEquals(after, tombstoneLines().size(), "an equal or older tombstone is already durable here");
    }

    @Test
    public void test_a_replicated_delete_spares_a_newer_stored_version() {
        assertTrue(
                ReplicatedApplyHelper.apply(
                        new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                                List.of(doc("newer"), doc("older")), null, List.of("50", "50")),
                        TestGlobals.APPLY_BUDGET_MS));

        assertTrue(ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                ReplicationOp.DELETE, null, List.of("newer", "older"), List.of("40", "60")),
                TestGlobals.APPLY_BUDGET_MS));

        assertEquals(OperationStatus.OK, findStatus("newer"));
        assertEquals(OperationStatus.NOT_FOUND, findStatus("older"));
    }

    @Test
    public void test_a_replica_does_not_cache_a_non_owned_document() throws Exception {
        assertTrue(applyServing(false, new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("unowned")), null, List.of("30"))));

        assertFalse(cached("unowned"));
        assertEquals(OperationStatus.OK, findStatus("unowned"));
    }

    @Test
    public void test_a_replicated_update_evicts_a_cached_copy() throws Exception {
        assertTrue(applyServing(true, new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("evicted")), null, List.of("31"))));
        assertTrue(cached("evicted"), "an owned collection keeps its documents cached");

        final var updated = doc("evicted");
        updated.addProperty("v", 2);
        assertTrue(applyServing(false, new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(updated), null, List.of("32"))));

        assertFalse(cached("evicted"), "the stale cached copy must not outlive the update");
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("evicted");
        final var found = (org.techhouse.ops.resp.FindByIdResponse) processor.processMessage(request);
        assertEquals(2, found.object.get("v").asJsonNumber().getValue().intValue());
    }
}
