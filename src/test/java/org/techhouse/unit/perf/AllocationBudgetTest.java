package org.techhouse.unit.perf;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.EntityRef;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.conn.ClientTracker;
import org.techhouse.conn.OutboundQueue;
import org.techhouse.data.DbEntry;
import org.techhouse.data.Transaction;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ResultHasher;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.CountOperatorHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.PipelineContext;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.admin.AdminPageHelper;
import org.techhouse.ops.admin.CollectionIncarnation;
import org.techhouse.ops.admin.ReadPathHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AllocationBudgetTest {
    private static final int DOCS = 10_000;
    private static final int BULK = 1_000;
    private static final int WARM_UPS = 5;
    private static final long KB = 1024L;
    private static final long SAVE_UPDATE_BUDGET = 25_430;
    private static final long INSERT_BUDGET_PER_DOC = 3 * KB;
    private static final long COLD_SCAN_BUDGET_PER_DOC = 3_570;
    private static final long BATCH_OF_ONE_BUDGET = 8_430;
    private static final long BULK_UPDATE_BUDGET_PER_DOC = 3_630;
    private static final long INDEXED_ID_LOOKUP_BUDGET = 21_510;
    private static final long EQUALS_INDEX_COUNT_BUDGET = 2_735;
    private static final long SAVE_REQUEST_PARSE_BUDGET = 3_120;
    private static final long LOCKED_COLD_SCAN_BUDGET_PER_DOC = 4_035;
    private static final long INDEXED_FILTER_LIMIT_BUDGET = 3_690;
    private static final long WRITE_PATH_PAGE_BOOKING_BUDGET = 915;
    private static final long BUFFERED_SAVE_BUDGET = 5_385;
    private static final long INDEXED_FILTER_LIMIT_IN_TRANSACTION_BUDGET = 23_125;
    private static final long DIRTY_FIND_BY_ID_BUDGET = 645;
    private static final double RESPONSE_BUDGET_RATIO = 1.3;
    private static final double FIRST_ENCODE_BUDGET_RATIO = 2.4;
    private static final double RE_ENCODE_BUDGET_RATIO = 1.1;
    private static final double LISTEN_HASH_BUDGET_RATIO = 1.4;
    private static final long UNORDERED_HASH_BUDGET_PER_DOC = 96;
    private static final String COLD_SCAN = """
            {"type":"AGGREGATE","databaseName":"%s","collectionName":"%s","aggregationSteps":[
            {"type":"FILTER","operator":{"fieldOperatorType":"SMALLER_THAN","field":"qty","value":1}},
            {"type":"COUNT"}]}""";

    private static final String INDEXED_FILTER_LIMIT = """
            {"type":"AGGREGATE","databaseName":"%s","collectionName":"%s","aggregationSteps":[
            {"type":"FILTER","operator":{"fieldOperatorType":"EQUALS","field":"cat","value":"c3"}},
            {"type":"LIMIT","limit":5}]}""";

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
    void lockedColdScanAllocatesUnderBudgetPerDoc() throws Exception {
        final var request = (AggregateRequest) RequestParser
                .parseRequest(COLD_SCAN.formatted(TestGlobals.DB, TestGlobals.COLL));
        final var documents = cache.pkIndexSize(TestGlobals.DB, TestGlobals.COLL);
        final var perDoc = cheapestOf(() -> {
            cache.evictCollection(TestGlobals.DB, TestGlobals.COLL);
            holdingTheCollectionRead(() -> AggregationOperationHelper.processAggregation(request));
        }) / documents;
        assertEquals(documents, cache.userCache().getCachedCollection(TestGlobals.DB, TestGlobals.COLL).size(),
                "a complete locked scan within the budget admits the collection it read");
        assertTrue(perDoc <= LOCKED_COLD_SCAN_BUDGET_PER_DOC,
                report("one document of a locked cold scan", perDoc, LOCKED_COLD_SCAN_BUDGET_PER_DOC));
    }

    @Test
    void indexedFilterLimitAllocatesUnderBudget() throws Exception {
        final var request = (AggregateRequest) RequestParser
                .parseRequest(INDEXED_FILTER_LIMIT.formatted(TestGlobals.DB, TestGlobals.COLL));
        IocContainer.get(PendingIndexWrites.class).clearCollection(TestGlobals.DB, TestGlobals.COLL);
        holdingTheCollectionRead(() -> AggregationOperationHelper.processAggregation(request));
        final var measured = cheapestOf(
                () -> holdingTheCollectionRead(() -> AggregationOperationHelper.processAggregation(request)));
        assertTrue(measured <= INDEXED_FILTER_LIMIT_BUDGET,
                report("an indexed FILTER + LIMIT 5", measured, INDEXED_FILTER_LIMIT_BUDGET));
    }

    private static void holdingTheCollectionRead(ThrowingAction action) throws Exception {
        assertTrue(locks.tryLockRead(TestGlobals.DB, TestGlobals.COLL, 5_000));
        try {
            action.run();
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    @Test
    void dirtyFindByIdAllocatesUnderBudget() throws Exception {
        holdingTheCollectionRead(() -> cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL));
        final int[] next = {0};
        final var measured = cheapestOf(() -> {
            final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
            request.set_id("d" + next[0]++ * 41 % DOCS);
            request.setDirtyRead(true);
            assertInstanceOf(FindByIdResponse.class, ReadPathHelper.processFindByIdOperation(request, null));
        });
        assertTrue(measured <= DIRTY_FIND_BY_ID_BUDGET,
                report("a dirty FIND_BY_ID", measured, DIRTY_FIND_BY_ID_BUDGET));
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
    void writePathPageBookingOfOneEventAllocatesUnderBudget() throws Exception {
        final var incarnation = CollectionIncarnation.current(TestGlobals.DB, TestGlobals.COLL);
        final var updated = List.of(new EntityRef("d1", 0L, 600L, 590L));
        final var measured = cheapestOf(() -> AdminPageHelper.bookWritePathPages(TestGlobals.DB, TestGlobals.COLL,
                List.of(), updated, incarnation));
        assertTrue(measured <= WRITE_PATH_PAGE_BOOKING_BUDGET,
                report("the page booking of one event", measured, WRITE_PATH_PAGE_BOOKING_BUDGET));
    }

    @Test
    void indexedIdLookupAllocatesUnderBudget() throws Exception {
        final var operator = new FieldOperator(FieldOperatorType.EQUALS, "cat", new JsonString("c3"));
        assertTrue(locks.tryLockRead(TestGlobals.DB, TestGlobals.COLL, 5_000));
        try {
            final var measured = cheapestOf(
                    () -> cache.getIdsFromIndex(TestGlobals.DB, TestGlobals.COLL, "cat", operator, "c3"));
            assertTrue(measured <= INDEXED_ID_LOOKUP_BUDGET,
                    report("an indexed lookup of 500 ids", measured, INDEXED_ID_LOOKUP_BUDGET));
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    @Test
    void equalsIndexOnlyCountAllocatesUnderBudget() throws Exception {
        final var steps = List.of(
                new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "cat", new JsonString("c3"))),
                new CountAggregationStep());
        final var pending = IocContainer.get(PendingIndexWrites.class);
        final var stillPending = pending.idsFor(TestGlobals.DB, TestGlobals.COLL);
        pending.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(locks.tryLockRead(TestGlobals.DB, TestGlobals.COLL, 5_000));
        try {
            final var counted = CountOperatorHelper.tryIndexOnlyCount(steps, TestGlobals.DB, TestGlobals.COLL,
                    PipelineContext.withoutScripts());
            assertNotNull(counted, "an EQUALS on an indexed field is counted from the index");
            assertEquals(DOCS / 20, counted.result().get("count").asJsonNumber().asInteger());
            final var measured = cheapestOf(() -> CountOperatorHelper.tryIndexOnlyCount(steps, TestGlobals.DB,
                    TestGlobals.COLL, PipelineContext.withoutScripts()));
            assertTrue(measured <= EQUALS_INDEX_COUNT_BUDGET,
                    report("an index-only COUNT of a 500-id EQUALS", measured, EQUALS_INDEX_COUNT_BUDGET));
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
            pending.mark(TestGlobals.DB, TestGlobals.COLL, stillPending);
        }
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

    @Test
    void encodingALargeDocumentStaysCloseToItsSize() throws Exception {
        final var rows = new JsonArray();
        for (var i = 0; i < 2600; i++) {
            rows.add(document(i, i % 97));
        }
        final var data = new JsonObject();
        data.add("rows", rows);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.set_id("large");
        final var size = entry.encodeLine().length;
        assertTrue(size > 1_000_000, String.valueOf(size));
        final var firstEncode = cheapestOf(() -> {
            entry.setData(data);
            entry.encodeLine();
        });
        final var firstBudget = (long) (size * FIRST_ENCODE_BUDGET_RATIO);
        assertTrue(firstEncode <= firstBudget,
                report("the first encode of a " + size + "-byte document", firstEncode, firstBudget));
        final var reEncode = cheapestOf(entry::encodeLine);
        final var reBudget = (long) (size * RE_ENCODE_BUDGET_RATIO);
        assertTrue(reEncode <= reBudget, report("re-encoding a " + size + "-byte document", reEncode, reBudget));
    }

    @Test
    void listenHashAllocatesCloseToTheResultSize() throws Exception {
        final var rows = new ArrayList<JsonObject>();
        for (var i = 0; i < 2000; i++) {
            rows.add(document(i, i % 97));
        }
        final long body;
        try (var encoded = ResultHasher.encode(rows, true)) {
            body = encoded.body().length();
        }
        final var ordered = cheapestOf(() -> ResultHasher.encode(rows, true).close());
        final var orderedBudget = (long) (body * LISTEN_HASH_BUDGET_RATIO);
        assertTrue(ordered <= orderedBudget, report("an ordered hash of " + body + " bytes", ordered, orderedBudget));
        final var unordered = cheapestOf(() -> ResultHasher.encode(rows, false).close());
        final var unorderedBudget = orderedBudget + UNORDERED_HASH_BUDGET_PER_DOC * rows.size();
        assertTrue(unordered <= unorderedBudget,
                report("an unordered hash of " + body + " bytes", unordered, unorderedBudget));
    }

    @Test
    void parsingASaveRequestStaysSmall() throws Exception {
        final var line = "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"object\":" + new EJson().toJson(document(1, 1)) + "}";
        final var measured = cheapestOf(() -> RequestParser.parseRequest(line));
        assertTrue(measured <= SAVE_REQUEST_PARSE_BUDGET,
                report("parsing a " + line.length() + "-char SAVE request", measured, SAVE_REQUEST_PARSE_BUDGET));
    }

    @Test
    void bufferedSaveAllocatesUnderBudget() throws Exception {
        inTransaction(transaction -> {
            final int[] next = {0};
            final var measured = cheapestOf(() -> {
                final var i = next[0]++ * 41 % DOCS;
                final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
                request.set_id("d" + i);
                request.setObject(document(i, 7));
                assertEquals(OperationStatus.OK,
                        TransactionOperationHelper.bufferSave(request, transaction).getStatus());
            });
            assertTrue(measured <= BUFFERED_SAVE_BUDGET, report("one buffered SAVE", measured, BUFFERED_SAVE_BUDGET));
        });
    }

    @Test
    void indexedFilterLimitInTransactionAllocatesUnderBudget() throws Exception {
        inTransaction(transaction -> {
            final var buffered = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
            buffered.set_id("d3");
            buffered.setObject(document(3, 9));
            assertEquals(OperationStatus.OK, TransactionOperationHelper.bufferSave(buffered, transaction).getStatus());
            final var request = (AggregateRequest) RequestParser
                    .parseRequest(INDEXED_FILTER_LIMIT.formatted(TestGlobals.DB, TestGlobals.COLL));
            IocContainer.get(PendingIndexWrites.class).clearCollection(TestGlobals.DB, TestGlobals.COLL);
            ReadPathHelper.processAggregateOperation(request, transaction);
            final var measured = cheapestOf(() -> assertEquals(OperationStatus.OK,
                    ReadPathHelper.processAggregateOperation(request, transaction).getStatus()));
            assertTrue(measured <= INDEXED_FILTER_LIMIT_IN_TRANSACTION_BUDGET,
                    report("an indexed FILTER + LIMIT 5 inside a transaction", measured,
                            INDEXED_FILTER_LIMIT_IN_TRANSACTION_BUDGET));
        });
    }

    private static void inTransaction(TransactionAction action) throws Exception {
        final var clientTracker = IocContainer.get(ClientTracker.class);
        final var clientId = clientTracker.registerForwardedClient("budget");
        TestUtils.ensureAdminUser(clientTracker.getAuthenticatedUsername(clientId));
        TransactionOperationHelper.start(clientId);
        try {
            action.run(clientTracker.getActiveTransaction(clientId));
        } finally {
            TransactionOperationHelper.rollback(clientId);
            clientTracker.removeById(clientId);
        }
    }

    @FunctionalInterface
    private interface TransactionAction {
        void run(Transaction transaction) throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
