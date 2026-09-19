package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReplicatedApplyVersionTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject doc(String id, String value) {
        final var object = new JsonObject();
        object.addProperty("_id", id);
        object.addProperty("value", value);
        return object;
    }

    private boolean applyUpsert(String id, String value, long version) {
        return ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                ReplicationOp.UPSERT, List.of(doc(id, value)), null, List.of(Long.toString(version))));
    }

    private boolean applyDelete(long version) {
        return ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                ReplicationOp.DELETE, null, List.of("a"), List.of(Long.toString(version))));
    }

    private OperationResponse find(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request);
    }

    private String valueOf(String id) {
        final var found = find(id);
        if (!(found instanceof FindByIdResponse byId) || byId.getObject() == null) {
            return null;
        }
        return byId.getObject().get("value").asJsonString().getValue();
    }

    private void bulkSave() {
        final var request = new org.techhouse.ops.req.BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(doc("a", "bulk")));
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private long storedVersion() throws Exception {
        return IocContainer.get(org.techhouse.cache.Cache.class)
                .getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(entry -> entry.getValue().equals("a")).findFirst().orElseThrow().getVersion();
    }

    @Test
    public void test_a_bulk_updated_document_is_not_superseded_on_a_replica() throws Exception {
        assertTrue(applyUpsert("a", "old", 100L));
        bulkSave();
        final var shipped = storedVersion();

        assertTrue(shipped > 100L, "a bulk update must ship the version it assigned, not a reset 0");

        assertTrue(applyUpsert("b", "old", 100L));
        assertTrue(ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                ReplicationOp.UPSERT, List.of(doc("b", "bulk")), null, List.of(Long.toString(shipped)))));
        assertEquals("bulk", valueOf("b"));
    }

    @Test
    public void test_a_null_version_in_the_list_is_applied_as_unversioned() {
        assertTrue(ReplicatedApplyHelper
                .apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                        List.of(doc("n", "unversioned")), null, java.util.Collections.singletonList(null))));
        assertEquals("unversioned", valueOf("n"));
    }

    @Test
    public void test_a_newer_upsert_is_applied() {
        assertTrue(applyUpsert("a", "first", 100L));
        assertTrue(applyUpsert("a", "second", 200L));
        assertEquals("second", valueOf("a"));
    }

    @Test
    public void test_an_older_upsert_is_skipped_and_still_reports_success() {
        assertTrue(applyUpsert("a", "newer", 200L));
        assertTrue(applyUpsert("a", "older", 100L), "a refused older write is convergence, not a replication failure");
        assertEquals("newer", valueOf("a"));
    }

    @Test
    public void test_a_mixed_batch_applies_only_the_newer_documents() {
        assertTrue(applyUpsert("a", "newer", 200L));
        assertTrue(applyUpsert("b", "seed", 100L));

        assertTrue(ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                ReplicationOp.UPSERT, List.of(doc("a", "stale"), doc("b", "fresh")), null, List.of("150", "300"))));

        assertEquals("newer", valueOf("a"));
        assertEquals("fresh", valueOf("b"));
    }

    @Test
    public void test_an_older_delete_is_skipped_but_still_tombstoned() throws Exception {
        assertTrue(applyUpsert("a", "newer", 300L));
        assertTrue(applyDelete(100L));

        assertEquals(OperationStatus.OK, find("a").getStatus(), "the newer document must survive an older delete");
        assertEquals(100L, IocContainer.get(org.techhouse.fs.FileSystem.class)
                .readTombstones(TestGlobals.DB, TestGlobals.COLL).get("a"));
    }

    @Test
    public void test_a_newer_delete_is_applied() {
        assertTrue(applyUpsert("a", "old", 100L));
        assertTrue(applyDelete(300L));
        assertEquals(OperationStatus.NOT_FOUND, find("a").getStatus());
    }

    @Test
    public void test_equal_version_push_follows_the_same_order_as_anti_entropy() {
        assertTrue(applyUpsert("eq", "local", 500L));

        assertTrue(applyUpsert("eq", "peer", 500L));

        assertEquals("peer", valueOf("eq"),
                "anti-entropy pulls an equal-version winner, so an equal-version push must overwrite too or the two"
                        + " paths tie-break in opposite directions and the cluster never converges");
    }

    @Test
    public void test_an_upsert_older_than_a_local_tombstone_is_refused() {
        assertTrue(applyUpsert("t", "live", 100L));
        assertTrue(ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                ReplicationOp.DELETE, null, List.of("t"), List.of("200"))));
        assertNull(valueOf("t"));

        assertTrue(applyUpsert("t", "resurrected", 150L),
                "a refused apply still reports success: the replica has converged, it has not failed");

        assertNull(valueOf("t"),
                "an upsert older than the local tombstone must be refused, or the committed delete is undone"
                        + " until the next anti-entropy round repairs it");
    }

    @Test
    public void test_an_upsert_newer_than_a_local_tombstone_is_applied() {
        assertTrue(applyUpsert("u", "live", 100L));
        assertTrue(ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                ReplicationOp.DELETE, null, List.of("u"), List.of("200"))));

        assertTrue(applyUpsert("u", "rewritten", 300L));

        assertEquals("rewritten", valueOf("u"));
    }
}
