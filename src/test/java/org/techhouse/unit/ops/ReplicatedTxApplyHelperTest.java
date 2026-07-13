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
import org.techhouse.cluster.msg.TxReplicationPayload;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.ReplicatedTxApplyHelper;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReplicatedTxApplyHelperTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

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
    public void test_null_or_empty_batch_returns_false() {
        assertFalse(ReplicatedTxApplyHelper.apply(null));
        assertFalse(ReplicatedTxApplyHelper.apply(new TxReplicationPayload()));
    }

    @Test
    public void test_applies_upsert_and_delete_entries_atomically() throws Exception {
        // Seed a document to be deleted by the batch.
        ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("tx-del")), null));
        assertEquals(OperationStatus.OK, findStatus("tx-del"));

        final var upsert = new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("tx-a"), doc("tx-b")), null, List.of(10L, 11L));
        final var delete = new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.DELETE, null,
                List.of("tx-del"), List.of(12L));

        assertTrue(ReplicatedTxApplyHelper.apply(new TxReplicationPayload(List.of(upsert, delete))));

        assertEquals(OperationStatus.OK, findStatus("tx-a"));
        assertEquals(OperationStatus.OK, findStatus("tx-b"));
        assertEquals(OperationStatus.NOT_FOUND, findStatus("tx-del"));
        assertEquals(12L, fs.readTombstones(TestGlobals.DB, TestGlobals.COLL).get("tx-del"));
    }
}
