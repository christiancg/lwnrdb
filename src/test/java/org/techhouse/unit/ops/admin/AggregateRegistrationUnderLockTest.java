package org.techhouse.unit.ops.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.techhouse.test.LockHolder.startParked;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.admin.ReadPathHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class AggregateRegistrationUnderLockTest {
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var document = new JsonObject();
        document.addProperty(Globals.PK_FIELD, "present");
        request.setObject(document);
        request.set_id("present");
        SaveOperationHelper.executeSave(request);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private ReentrantReadWriteLock collectionLock() throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, ReentrantReadWriteLock>>() {
        };
        return TestUtils.getPrivateField(locks, "locks", type)
                .get(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL));
    }

    private static OperationResponse aggregate() {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of());
        return ReadPathHelper.processAggregateOperation(request, null);
    }

    @Test
    public void test_an_aggregate_queued_behind_a_drop_answers_not_found() throws Exception {
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        final var result = new AtomicReference<OperationResponse>();
        final var pending = startParked(AggregateRegistrationUnderLockTest::aggregate, result);
        assertTrue(collectionLock().hasQueuedThreads(), "the aggregate never queued on the collection lock");
        AdminOperationHelper.deleteCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        cache.evictCollection(TestGlobals.DB, TestGlobals.COLL);
        locks.release(TestGlobals.DB, TestGlobals.COLL);

        pending.join(5000);

        assertEquals("404-11", result.get().getErrorCode(),
                "a read that passed the registration check before the drop took its lock reads a dropped name");
    }

    @Test
    public void test_an_aggregate_of_a_registered_collection_still_answers() {
        final var response = aggregate();

        assertNotEquals("404-11", response.getErrorCode());
        assertEquals(OperationStatus.OK, response.getStatus());
    }
}
