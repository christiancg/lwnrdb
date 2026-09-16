package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                ReplicationOp.UPSERT, List.of(doc(id, value)), null, List.of(version)));
    }

    private boolean applyDelete(long version) {
        return ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                ReplicationOp.DELETE, null, List.of("a"), List.of(version)));
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
                ReplicationOp.UPSERT, List.of(doc("a", "stale"), doc("b", "fresh")), null, List.of(150L, 300L))));

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
}
