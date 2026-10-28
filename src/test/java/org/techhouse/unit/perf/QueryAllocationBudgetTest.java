package org.techhouse.unit.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class QueryAllocationBudgetTest {
    private static final int DOCS = 10_000;
    private static final int BULK = 1_000;
    private static final int WARM_UPS = 5;
    private static final long PAGE_SIZE = 64 * 1024L;
    private static final long SKIP_SOURCE_PAGE_BUDGET = 433_170;
    private static final long GROUP_MAP_REMOVE_BUDGET_PER_DOC = 107;
    private static final String SKIP_PAGE = """
            {"type":"AGGREGATE","databaseName":"%s","collectionName":"%s","aggregationSteps":[
            {"type":"SKIP","skip":9000},{"type":"LIMIT","limit":100}]}""";
    private static final String GROUP_MAP_REMOVE = """
            {"type":"AGGREGATE","databaseName":"%s","collectionName":"%s","aggregationSteps":[
            {"type":"GROUP_BY","fieldName":"cat"},
            {"type":"MAP","operators":[
            {"fieldName":"size","operator":{"type":"SIZE","operand":"group"}},
            {"fieldName":"group"}]}]}""";
    private static final String WARM = """
            {"type":"AGGREGATE","databaseName":"%s","collectionName":"%s","aggregationSteps":[
            {"type":"SKIP","skip":0}]}""";
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory
            .getThreadMXBean();
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static long shippedPageSize;

    @BeforeAll
    static void loadFixture() throws Exception {
        TestUtils.standardInitialSetup();
        shippedPageSize = configuration.getMaxPageSize();
        TestUtils.setPrivateField(configuration, "maxPageSize", PAGE_SIZE);
        TestUtils.createTestDatabaseAndCollection();
        for (var start = 0; start < DOCS; start += BULK) {
            final var objects = new ArrayList<JsonObject>(BULK);
            for (var i = start; i < start + BULK; i++) {
                objects.add(document(i));
            }
            final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
            request.setObjects(objects);
            locks.lock(TestGlobals.DB, TestGlobals.COLL);
            try {
                SaveOperationHelper.executeBulkSave(request);
            } finally {
                locks.release(TestGlobals.DB, TestGlobals.COLL);
            }
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", shippedPageSize);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(int i) {
        final var tags = new JsonArray();
        tags.add(new JsonString("alpha"));
        tags.add(new JsonString("t" + i % 50));
        final var doc = new JsonObject();
        doc.add("_id", new JsonString("q" + i));
        doc.add("name", new JsonString("Customer number " + i));
        doc.add("cat", new JsonString("c" + i % 20));
        doc.add("price", new JsonNumber(i * 1.5));
        doc.add("tags", tags);
        doc.add("notes", new JsonString("lorem ipsum dolor sit amet ".repeat(8)));
        return doc;
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static long cheapestOf(ThrowingAction action) throws Exception {
        var cheapest = Long.MAX_VALUE;
        for (var round = 0; round < WARM_UPS; round++) {
            final var before = THREADS.getCurrentThreadAllocatedBytes();
            action.run();
            cheapest = Math.min(cheapest, THREADS.getCurrentThreadAllocatedBytes() - before);
        }
        return cheapest;
    }

    private static List<JsonObject> underReadLock(AggregateRequest request) throws Exception {
        assertTrue(locks.tryLockRead(TestGlobals.DB, TestGlobals.COLL, 5_000));
        try {
            return AggregationOperationHelper.processAggregation(request);
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    private static AggregateRequest request(String template) {
        return (AggregateRequest) RequestParser
                .parseRequest(String.format(template, TestGlobals.DB, TestGlobals.COLL).replace("\n", ""));
    }

    private static String report(String what, long measured, long budget) {
        return what + " allocated " + measured + " bytes against a budget of " + budget;
    }

    @Test
    void aSourceSkipReadsOnlyThePagesItReturns() throws Exception {
        final var request = request(SKIP_PAGE);
        final var measured = cheapestOf(() -> {
            cache.userCache().evictCollectionDocuments(TestGlobals.DB, TestGlobals.COLL);
            assertEquals(100, underReadLock(request).size());
        });
        assertTrue(measured <= SKIP_SOURCE_PAGE_BUDGET,
                report("a SKIP 9000 + LIMIT 100 page", measured, SKIP_SOURCE_PAGE_BUDGET));
    }

    @Test
    void aMapRemovingTheGroupCopiesNoGroup() throws Exception {
        final var request = request(GROUP_MAP_REMOVE);
        assertEquals(DOCS, underReadLock(request(WARM)).size());
        assertTrue(cache.holdsWholeCollection(TestGlobals.DB, TestGlobals.COLL));
        final var measured = cheapestOf(() -> assertEquals(20, underReadLock(request).size())) / DOCS;
        assertTrue(measured <= GROUP_MAP_REMOVE_BUDGET_PER_DOC,
                report("a grouped document whose group a MAP removes", measured, GROUP_MAP_REMOVE_BUDGET_PER_DOC));
    }
}
