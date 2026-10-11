package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.admin.CollectionIncarnation;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReplicatedApplyVersionTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

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
                ReplicationOp.UPSERT, List.of(doc(id, value)), null, List.of(Long.toString(version))),
                TestGlobals.APPLY_BUDGET_MS);
    }

    private boolean applyDelete(long version) {
        return ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                ReplicationOp.DELETE, null, List.of("a"), List.of(Long.toString(version))),
                TestGlobals.APPLY_BUDGET_MS);
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
        assertTrue(ReplicatedApplyHelper.apply(
                new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                        List.of(doc("b", "bulk")), null, List.of(Long.toString(shipped))),
                TestGlobals.APPLY_BUDGET_MS));
        assertEquals("bulk", valueOf("b"));
    }

    @Test
    public void test_a_null_version_in_the_list_is_applied_as_unversioned() {
        assertTrue(ReplicatedApplyHelper.apply(
                new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                        List.of(doc("n", "unversioned")), null, java.util.Collections.singletonList(null)),
                TestGlobals.APPLY_BUDGET_MS));
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

        assertTrue(ReplicatedApplyHelper.apply(
                new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                        List.of(doc("a", "stale"), doc("b", "fresh")), null, List.of("150", "300")),
                TestGlobals.APPLY_BUDGET_MS));

        assertEquals("newer", valueOf("a"));
        assertEquals("fresh", valueOf("b"));
    }

    @Test
    public void test_an_older_delete_is_skipped_but_still_tombstoned() throws Exception {
        assertTrue(applyUpsert("a", "newer", 300L));
        assertTrue(applyDelete(100L));

        assertEquals(OperationStatus.OK, find("a").getStatus(), "the newer document must survive an older delete");
        assertEquals(100L, fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL).get("a"));
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
                ReplicationOp.DELETE, null, List.of("t"), List.of("200")), TestGlobals.APPLY_BUDGET_MS));
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
                ReplicationOp.DELETE, null, List.of("u"), List.of("200")), TestGlobals.APPLY_BUDGET_MS));

        assertTrue(applyUpsert("u", "rewritten", 300L));

        assertEquals("rewritten", valueOf("u"));
    }

    private static JsonObject idOnly(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        return object;
    }

    private void create(String collName) {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, collName)).getStatus());
    }

    private OperationStatus findStatus(String collName, String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, collName);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    private static ReplicationPayload upsert(String collName, long incarnation, String id) {
        final var payload = new ReplicationPayload(TestGlobals.DB, collName, ReplicationOp.UPSERT, List.of(idOnly(id)),
                null, List.of("100"));
        payload.setIncarnationValue(incarnation);
        return payload;
    }

    @Test
    public void test_an_upsert_of_the_current_incarnation_applies() {
        create("incCurrent");
        final var incarnation = CollectionIncarnation.current(TestGlobals.DB, "incCurrent");

        assertTrue(ReplicatedApplyHelper.apply(upsert("incCurrent", incarnation, "a"), TestGlobals.APPLY_BUDGET_MS));
        assertEquals(OperationStatus.OK, findStatus("incCurrent", "a"));
    }

    @Test
    public void test_an_upsert_of_another_incarnation_is_refused() {
        create("incOther");
        final var incarnation = CollectionIncarnation.current(TestGlobals.DB, "incOther");

        assertFalse(ReplicatedApplyHelper.apply(upsert("incOther", incarnation + 1, "a"), TestGlobals.APPLY_BUDGET_MS));
        assertEquals(OperationStatus.NOT_FOUND, findStatus("incOther", "a"));
    }

    @Test
    public void test_a_stamped_upsert_to_an_unregistered_collection_writes_nothing() {
        assertFalse(ReplicatedApplyHelper.apply(upsert("incNeverCreated", 42L, "a"), TestGlobals.APPLY_BUDGET_MS));
        assertFalse(new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + "incNeverCreated")
                .exists());
    }

    @Test
    public void test_an_unstamped_upsert_applies_as_before() {
        create("incLegacy");

        assertTrue(ReplicatedApplyHelper.apply(upsert("incLegacy", 0L, "a"), TestGlobals.APPLY_BUDGET_MS));
        assertEquals(OperationStatus.OK, findStatus("incLegacy", "a"));
    }

    @Test
    public void test_a_delete_of_another_incarnation_is_refused() throws Exception {
        create("incDelete");
        final var incarnation = CollectionIncarnation.current(TestGlobals.DB, "incDelete");
        assertTrue(ReplicatedApplyHelper.apply(upsert("incDelete", incarnation, "kept"), TestGlobals.APPLY_BUDGET_MS));

        final var delete = new ReplicationPayload(TestGlobals.DB, "incDelete", ReplicationOp.DELETE, null,
                List.of("kept"), List.of("200"));
        delete.setIncarnationValue(incarnation + 1);

        assertFalse(ReplicatedApplyHelper.apply(delete, TestGlobals.APPLY_BUDGET_MS));
        assertEquals(OperationStatus.OK, findStatus("incDelete", "kept"));
        assertFalse(fs.tombstones().read(TestGlobals.DB, "incDelete").containsKey("kept"));
    }

    @Test
    public void test_a_payload_built_before_a_drop_and_recreate_does_not_seed_the_new_collection() {
        create("incRecreated");
        final var before = CollectionIncarnation.current(TestGlobals.DB, "incRecreated");
        final var stale = upsert("incRecreated", before, "dead");

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(TestGlobals.DB, "incRecreated")).getStatus());
        create("incRecreated");

        assertNotEquals(before, CollectionIncarnation.current(TestGlobals.DB, "incRecreated"));
        assertFalse(ReplicatedApplyHelper.apply(stale, TestGlobals.APPLY_BUDGET_MS));
        assertEquals(OperationStatus.NOT_FOUND, findStatus("incRecreated", "dead"),
                "a pre-drop document applied into the new incarnation is replicated cluster-wide from there");
    }
}
