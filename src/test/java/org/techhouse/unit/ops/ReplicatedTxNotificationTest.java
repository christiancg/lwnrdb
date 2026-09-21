package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.cluster.msg.TxReplicationPayload;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ReplicatedTxApplyHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReplicatedTxNotificationTest {
    private static final String SECOND_COLL = "replicaSecond";

    private final ListenManager listenManager = IocContainer.get(ListenManager.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        IocContainer.get(org.techhouse.fs.FileSystem.class).createCollectionFile(TestGlobals.DB, SECOND_COLL);
        AdminOperationHelper.createPageCollections(TestGlobals.DB, SECOND_COLL);
        AdminOperationHelper
                .saveCollectionEntry(new org.techhouse.data.admin.AdminCollEntry(TestGlobals.DB, SECOND_COLL));
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() {
        listenManager.unregisterAllForCollection(TestGlobals.DB, TestGlobals.COLL);
        listenManager.unregisterAllForCollection(TestGlobals.DB, SECOND_COLL);
        queuedListenIds().clear();
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> queuedListenIds() {
        try {
            return TestUtils.getPrivateField(listenManager, "queued", Set.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID listenOn(String collName) {
        final var request = new AggregateRequest(TestGlobals.DB, collName);
        request.setAggregationSteps(List.of());
        request.setDirtyRead(true);
        return listenManager.register(UUID.randomUUID(), request, "hash");
    }

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        return object;
    }

    private static ReplicationPayload upsert(String collName, String id, String version) {
        return new ReplicationPayload(TestGlobals.DB, collName, ReplicationOp.UPSERT, List.of(doc(id)), null,
                List.of(version));
    }

    @Test
    public void test_a_multi_op_batch_still_notifies_its_listener() {
        final var listenId = listenOn(TestGlobals.COLL);

        assertTrue(
                ReplicatedTxApplyHelper.apply(new TxReplicationPayload(List.of(upsert(TestGlobals.COLL, "n-a", "100"),
                        upsert(TestGlobals.COLL, "n-b", "101"), upsert(TestGlobals.COLL, "n-c", "102")))));

        assertTrue(queuedListenIds().contains(listenId), "deferring must not swallow the batch's notification");
    }

    @Test
    public void test_the_batch_produces_one_notification_rather_than_one_per_op() {
        final var listenId = listenOn(TestGlobals.COLL);

        ReplicatedTxApplyHelper.apply(new TxReplicationPayload(
                List.of(upsert(TestGlobals.COLL, "one-a", "110"), upsert(TestGlobals.COLL, "one-b", "111"))));

        assertEquals(Set.of(listenId), queuedListenIds());
    }

    @Test
    public void test_every_collection_the_batch_touched_is_notified() {
        final var first = listenOn(TestGlobals.COLL);
        final var second = listenOn(SECOND_COLL);

        assertTrue(ReplicatedTxApplyHelper.apply(new TxReplicationPayload(
                List.of(upsert(TestGlobals.COLL, "multi-a", "120"), upsert(SECOND_COLL, "multi-b", "121")))));

        assertEquals(Set.of(first, second), queuedListenIds(),
                "the flush must cover every collection the batch wrote, not only the last one");
    }

    @Test
    public void test_the_deferral_does_not_leak_onto_the_applying_thread() throws Exception {
        listenOn(TestGlobals.COLL);
        ReplicatedTxApplyHelper.apply(new TxReplicationPayload(List.of(upsert(TestGlobals.COLL, "leak", "130"))));

        final var deferred = TestUtils.getPrivateField(listenManager, "deferred", ThreadLocal.class);

        assertFalse(deferred.get() instanceof Set,
                "a window left open would silently hold back every later notification on this thread");
    }

    @Test
    public void test_an_empty_batch_opens_no_window() throws Exception {
        assertFalse(ReplicatedTxApplyHelper.apply(new TxReplicationPayload(List.of())));

        final var deferred = TestUtils.getPrivateField(listenManager, "deferred", ThreadLocal.class);

        assertFalse(deferred.get() instanceof Set);
    }
}
