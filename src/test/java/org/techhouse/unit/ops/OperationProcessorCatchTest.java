package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.AdminCache;
import org.techhouse.cache.Cache;
import org.techhouse.cache.UserCache;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.GetDatabaseStatsRequest;
import org.techhouse.ops.req.ListCollectionsRequest;
import org.techhouse.ops.req.ListDatabasesRequest;
import org.techhouse.ops.req.ListUsersRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.SetDatabaseOwnersRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OperationProcessorCatchTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private AdminCache realAdminCache;
    private UserCache realUserCache;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        realAdminCache = TestUtils.getPrivateField(cache, "adminCache", AdminCache.class);
        realUserCache = TestUtils.getPrivateField(cache, "userCache", UserCache.class);
        final var throwing = mock(AdminCache.class, invocation -> {
            throw new RuntimeException("boom");
        });
        final var throwingUser = mock(UserCache.class, invocation -> {
            throw new RuntimeException("boom");
        });
        TestUtils.setPrivateField(cache, "adminCache", throwing);
        TestUtils.setPrivateField(cache, "userCache", throwingUser);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(cache, "adminCache", realAdminCache);
        TestUtils.setPrivateField(cache, "userCache", realUserCache);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject doc() {
        final var obj = new JsonObject();
        obj.addProperty("_id", "x");
        obj.addProperty("v", 1);
        return obj;
    }

    private void assertErrors(OperationRequest request) {
        final OperationResponse response = processor.processMessage(request);
        assertNotNull(response);
        assertEquals(OperationStatus.ERROR, response.getStatus());
    }

    @Test
    public void test_data_operation_catch_blocks() {
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        save.setObject(doc());
        assertErrors(save);

        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(doc()));
        assertErrors(bulk);

        final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        find.set_id("x");
        assertErrors(find);

        final var aggregate = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        aggregate.setAggregationSteps(List.of());
        assertErrors(aggregate);

        final var delete = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        delete.set_id("x");
        assertErrors(delete);
    }

    @Test
    public void test_admin_operation_catch_blocks() {
        assertErrors(new CreateDatabaseRequest("anotherDb"));
        assertErrors(new ListDatabasesRequest());
        assertErrors(new CreateCollectionRequest(TestGlobals.DB, "newColl"));
        assertErrors(new DropCollectionRequest(TestGlobals.DB, TestGlobals.COLL));
        assertErrors(new ListCollectionsRequest(TestGlobals.DB));
        assertErrors(new ListUsersRequest());
        assertErrors(new GetDatabaseStatsRequest());
        final var owners = new SetDatabaseOwnersRequest(TestGlobals.DB);
        owners.setOwners(List.of("bob"));
        assertErrors(owners);
    }

    @Test
    public void test_index_operation_catch_blocks() {
        assertErrors(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "v"));
        assertErrors(new DropIndexRequest(TestGlobals.DB, TestGlobals.COLL, "v"));
        assertErrors(new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, List.of("v")));
    }
}
