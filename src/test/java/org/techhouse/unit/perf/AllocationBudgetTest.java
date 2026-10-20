package org.techhouse.unit.perf;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.conn.OutboundQueue;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AllocationBudgetTest {
    private static final int DOCS = 10_000;
    private static final int BULK = 1_000;
    private static final int WARM_UPS = 5;
    private static final long KB = 1024L;
    private static final long SAVE_UPDATE_BUDGET = 30 * KB;
    private static final long INSERT_BUDGET_PER_DOC = 3 * KB;
    private static final long COLD_SCAN_BUDGET_PER_DOC = 4 * KB;
    private static final long BATCH_OF_ONE_BUDGET = 11 * KB;
    private static final long BULK_UPDATE_BUDGET_PER_DOC = 4 * KB;
    private static final double RESPONSE_BUDGET_RATIO = 1.3;
    private static final String COLD_SCAN = """
            {"type":"AGGREGATE","databaseName":"%s","collectionName":"%s","aggregationSteps":[
            {"type":"FILTER","operator":{"fieldOperatorType":"SMALLER_THAN","field":"qty","value":1}},
            {"type":"COUNT"}]}""";

    private static final String INDEXED_LOOKUP = """
            {"type":"AGGREGATE","databaseName":"%s","collectionName":"%s","aggregationSteps":[
            {"type":"FILTER","operator":{"fieldOperatorType":"EQUALS","field":"%s","value":"c3"}}]}""";

    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory
            .getThreadMXBean();
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static int nextInsertId = DOCS;

    @BeforeAll
    static void loadFixture() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        for (var start = 0; start < DOCS; start += BULK) {
            bulkSave(start);
        }
        for (final var field : List.of("cat", "email")) {
            IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, field);
        }
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("cat", "email"));
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(int i, int qty) {
        final var tags = new JsonArray();
        tags.add(new JsonString("alpha"));
        tags.add(new JsonString("beta"));
        tags.add(new JsonString("t" + i % 50));
        final var address = new JsonObject();
        address.add("street", new JsonString(i + " Main Street"));
        address.add("city", new JsonString("City" + i % 300));
        address.add("zip", new JsonString(String.valueOf(10000 + i % 9000)));
        final var doc = new JsonObject();
        doc.add("_id", new JsonString("d" + i));
        doc.add("name", new JsonString("Customer number " + i));
        doc.add("email", new JsonString("user" + i + "@example.com"));
        doc.add("cat", new JsonString("c" + i % 20));
        doc.add("price", new JsonNumber(i * 1.5));
        doc.add("qty", new JsonNumber(qty));
        doc.add("active", JsonBoolean.of(i % 2 == 0));
        doc.add("tags", tags);
        doc.add("address", address);
        doc.add("notes", new JsonString("lorem ipsum dolor sit amet ".repeat(20)));
        return doc;
    }

    private static BulkSaveRequest bulkRequest(int start) {
        final var objects = new ArrayList<JsonObject>(BULK);
        for (var i = start; i < start + BULK; i++) {
            objects.add(document(i, i % 97));
        }
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(objects);
        return request;
    }

    private static void bulkSave(int start) throws Exception {
        holdingTheCollection(() -> SaveOperationHelper.executeBulkSave(bulkRequest(start)));
    }

    private static void holdingTheCollection(ThrowingAction action) throws Exception {
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            action.run();
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    private static void saveUpdate(int i) throws Exception {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("d" + i);
        request.setObject(document(i, 1 + i % 5));
        holdingTheCollection(() -> SaveOperationHelper.executeSave(request));
    }

    private static long allocatedBy(ThrowingAction action) throws Exception {
        final var before = THREADS.getCurrentThreadAllocatedBytes();
        action.run();
        return THREADS.getCurrentThreadAllocatedBytes() - before;
    }

    private static long cheapestOf(ThrowingAction action) throws Exception {
        var cheapest = Long.MAX_VALUE;
        for (var round = 0; round < WARM_UPS; round++) {
            cheapest = Math.min(cheapest, allocatedBy(action));
        }
        return cheapest;
    }

    private static String report(String what, long measured, long budget) {
        return what + " allocated " + measured + " bytes against a budget of " + budget;
    }

    @Test
    void saveUpdateAllocatesUnderBudget() throws Exception {
        final int[] next = {0};
        final var measured = cheapestOf(() -> saveUpdate(next[0]++ * 37 % DOCS));
        assertTrue(measured <= SAVE_UPDATE_BUDGET, report("one SAVE update", measured, SAVE_UPDATE_BUDGET));
    }

    @Test
    void insertAllocatesUnderBudgetPerDoc() throws Exception {
        var cheapest = Long.MAX_VALUE;
        for (var round = 0; round < WARM_UPS; round++) {
            final var request = bulkRequest(nextInsertId);
            nextInsertId += BULK;
            cheapest = Math.min(cheapest,
                    allocatedBy(() -> holdingTheCollection(() -> SaveOperationHelper.executeBulkSave(request))));
        }
        final var perDoc = cheapest / BULK;
        assertTrue(perDoc <= INSERT_BUDGET_PER_DOC, report("one inserted document", perDoc, INSERT_BUDGET_PER_DOC));
    }

    @Test
    void bulkUpdateAllocatesUnderBudgetPerDoc() throws Exception {
        var cheapest = Long.MAX_VALUE;
        for (var round = 0; round < WARM_UPS; round++) {
            final var objects = new ArrayList<JsonObject>(BULK);
            for (var i = round * BULK; i < (round + 1) * BULK; i++) {
                objects.add(document(i, 100 + round));
            }
            final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
            request.setObjects(objects);
            cheapest = Math.min(cheapest,
                    allocatedBy(() -> holdingTheCollection(() -> SaveOperationHelper.executeBulkSave(request))));
        }
        final var perDoc = cheapest / BULK;
        assertTrue(perDoc <= BULK_UPDATE_BUDGET_PER_DOC,
                report("one bulk-updated document", perDoc, BULK_UPDATE_BUDGET_PER_DOC));
    }

    @Test
    void coldScanAllocatesUnderBudgetPerDoc() throws Exception {
        final var request = (AggregateRequest) RequestParser
                .parseRequest(COLD_SCAN.formatted(TestGlobals.DB, TestGlobals.COLL));
        final var documents = cache.pkIndexSize(TestGlobals.DB, TestGlobals.COLL);
        final var perDoc = cheapestOf(() -> {
            cache.evictCollection(TestGlobals.DB, TestGlobals.COLL);
            AggregationOperationHelper.processAggregation(request);
        }) / documents;
        assertTrue(perDoc <= COLD_SCAN_BUDGET_PER_DOC,
                report("one cold-scanned document", perDoc, COLD_SCAN_BUDGET_PER_DOC));
    }

    @Test
    void backgroundBatchOfOneAllocatesUnderBudget() throws Exception {
        assertTrue(locks.tryLockRead(TestGlobals.DB, TestGlobals.COLL, 5_000));
        try {
            for (final var field : List.of("cat", "email")) {
                AggregationOperationHelper.processAggregation((AggregateRequest) RequestParser
                        .parseRequest(INDEXED_LOOKUP.formatted(TestGlobals.DB, TestGlobals.COLL, field)));
            }
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }
        final int[] next = {0};
        final var measured = cheapestOf(
                () -> IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, List.of("d" + next[0]++ * 53)));
        assertTrue(measured <= BATCH_OF_ONE_BUDGET,
                report("a background batch of one id", measured, BATCH_OF_ONE_BUDGET));
    }

    @Test
    void responseSerialisationStaysCloseToItsSize() throws Exception {
        final var rows = new ArrayList<JsonObject>();
        for (var i = 0; i < 2000; i++) {
            rows.add(document(i, i % 97));
        }
        final var response = new AggregateResponse("Ok", rows);
        final long[] size = {0};
        final var measured = cheapestOf(() -> size[0] = OutboundQueue.frameOf(response).length());
        final var budget = (long) (size[0] * RESPONSE_BUDGET_RATIO);
        assertTrue(measured <= budget, report("serialising a " + size[0] + "-byte response", measured, budget));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
