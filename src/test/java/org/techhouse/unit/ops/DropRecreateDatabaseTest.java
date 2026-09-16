package org.techhouse.unit.ops;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

import static org.junit.jupiter.api.Assertions.*;

public class DropRecreateDatabaseTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);

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

    private void save(String value) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("shared"));
        obj.addProperty("value", value);
        request.setObject(obj);
        request.set_id("shared");
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private void count() {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new CountAggregationStep()));
        processor.processMessage(request);
    }

    private String valueOf() {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("shared");
        final var response = processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertInstanceOf(FindByIdResponse.class, response);
        return ((FindByIdResponse) response).getObject().get("value").asJsonString().getValue();
    }

    @Test
    public void test_recreated_collection_does_not_inherit_the_dropped_pk_index() {
        save("before");
        count();
        // The state evictDatabase used to leave behind: the pk index is cached with no documents
        // beside it, which is what memory pressure and every index-only read produce.
        cache.userCache().evictCollectionDocuments(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(OperationStatus.OK, processor.processMessage(new DropDatabaseRequest(TestGlobals.DB)).getStatus());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateDatabaseRequest(TestGlobals.DB)).getStatus());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, TestGlobals.COLL)).getStatus());
        save("after");

        assertEquals("after", valueOf());
    }
}
