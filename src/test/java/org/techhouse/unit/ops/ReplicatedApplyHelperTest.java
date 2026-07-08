package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReplicatedApplyHelperTest {
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
}
