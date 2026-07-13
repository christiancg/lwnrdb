package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.ListCollectionsRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.SetDatabaseOwnersRequest;
import org.techhouse.test.TestUtils;

public class OperationProcessorErrorPathsTest {
    private static final String MISSING_DB = "missingDb";
    private static final String MISSING_COLL = "missingColl";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void assertNotOk(org.techhouse.ops.resp.OperationResponse response) {
        assertNotNull(response);
        assertNotEquals(OperationStatus.OK, response.getStatus());
    }

    private static JsonObject doc(String id) {
        final var obj = new JsonObject();
        obj.add("_id", new JsonString(id));
        return obj;
    }

    @Test
    public void test_writes_to_missing_collection_return_errors() {
        final var save = new SaveRequest(MISSING_DB, MISSING_COLL);
        save.setObject(doc("x"));
        assertNotOk(processor.processMessage(save));

        final var bulk = new BulkSaveRequest(MISSING_DB, MISSING_COLL);
        bulk.setObjects(List.of(doc("y")));
        assertNotOk(processor.processMessage(bulk));

        final var delete = new DeleteRequest(MISSING_DB, MISSING_COLL);
        delete.set_id("nope");
        assertNotOk(processor.processMessage(delete));
    }

    @Test
    public void test_admin_ops_on_missing_targets_return_errors() {
        assertNotOk(processor.processMessage(new DropDatabaseRequest(MISSING_DB)));
        assertNotOk(processor.processMessage(new DropCollectionRequest(MISSING_DB, MISSING_COLL)));
        assertNotNull(processor.processMessage(new CreateCollectionRequest(MISSING_DB, MISSING_COLL)));
        assertNotNull(processor.processMessage(new CreateIndexRequest(MISSING_DB, MISSING_COLL, "f")));
        assertNotNull(processor.processMessage(new DropIndexRequest(MISSING_DB, MISSING_COLL, "f")));
        assertNotNull(processor.processMessage(new ReindexRequest(MISSING_DB, MISSING_COLL, List.of("f"))));
        final var owners = new SetDatabaseOwnersRequest(MISSING_DB);
        owners.setOwners(List.of("bob"));
        assertNotNull(processor.processMessage(owners));
        assertNotNull(processor.processMessage(new ListCollectionsRequest(MISSING_DB)));
    }

    @Test
    public void test_aggregate_on_missing_collection() {
        final var request = new AggregateRequest(MISSING_DB, MISSING_COLL);
        request.setAggregationSteps(List.of());
        assertNotNull(processor.processMessage(request));
    }
}
