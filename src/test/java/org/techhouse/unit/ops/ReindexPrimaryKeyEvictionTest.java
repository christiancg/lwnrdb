package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.admin.PageOccupancyReconciler;
import org.techhouse.ops.index.IndexOperationHelper;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.PrimaryKeyReindexResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReindexPrimaryKeyEvictionTest {
    private static final String TAG = "tag";

    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final FileSystem realFs = IocContainer.get(FileSystem.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private BackgroundTaskManager originalTaskManager;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        originalTaskManager = TestUtils.getPrivateStaticField(PageOccupancyReconciler.class, "taskManager",
                BackgroundTaskManager.class);
        TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "taskManager", new BackgroundTaskManager());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(IndexOperationHelper.class, "fs", realFs);
        TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "taskManager", originalTaskManager);
        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void save(String id) {
        final var object = new JsonObject();
        object.addProperty("_id", id);
        object.addProperty(TAG, "tag-" + id);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private record AtPlan(boolean pkResident, boolean fieldIndexResident) {
    }

    @Test
    public void rebuildEvictsResidentFirst() throws Exception {
        save("a");
        save("b");
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, TAG)).getStatus());
        final var tagIndex = Cache.getIndexIdentifier(TAG, String.class);
        final List<?> pk;
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            pk = cache.userCache().getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
            cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, TAG, String.class);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
        assertNotNull(cache.residentFieldIndex(TestGlobals.DB, TestGlobals.COLL, tagIndex));
        assertTrue(cache.userCache().isResidentPkIndex(TestGlobals.DB, TestGlobals.COLL, cast(pk)));
        final var spied = spy(realFs);
        TestUtils.setPrivateStaticField(IndexOperationHelper.class, "fs", spied);
        final var seen = new CopyOnWriteArrayList<AtPlan>();
        doAnswer(invocation -> {
            seen.add(new AtPlan(cache.userCache().isResidentPkIndex(TestGlobals.DB, TestGlobals.COLL, cast(pk)),
                    cache.residentFieldIndex(TestGlobals.DB, TestGlobals.COLL, tagIndex) != null));
            return invocation.callRealMethod();
        }).when(spied).planPkRebuild(eq(TestGlobals.DB), eq(TestGlobals.COLL));

        final var response = processor
                .processMessage(new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, List.of(), true));

        assertInstanceOf(PrimaryKeyReindexResponse.class, response);
        assertEquals(List.of(new AtPlan(false, false)), seen,
                "the plan holds its own copy of every row, so the resident ones must not stay alive beside it");
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }
}
