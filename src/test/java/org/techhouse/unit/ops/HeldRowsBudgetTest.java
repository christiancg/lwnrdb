package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.RollbackTransactionRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class HeldRowsBudgetTest {
    private static final String REMOTE = "heldRemote";
    private static final String MIXED = "heldMixed";
    private static final int DOCUMENTS = 200;
    private static final int REMOTE_DOCUMENTS = 300;
    private static final String TIGHT = "16Kb";
    private static final String COUNT = "{\"type\":\"COUNT\"}";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Configuration configuration = Configuration.getInstance();

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var documents = new StringBuilder(DOCUMENTS * 256);
        for (var i = 0; i < DOCUMENTS; i++) {
            documents.append(i == 0 ? "" : ",").append("{\"_id\":\"d").append(i).append("\",\"n\":").append(i)
                    .append(",\"k\":\"g").append(i % 4).append("\",\"pad\":\"").repeat("x", 200).append("\"}");
        }
        assertOk(run(write("BULK_SAVE", TestGlobals.COLL, "\"objects\":[" + documents + "]")));
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "aggregateMaxResultBytes",
                ConfigKey.AGGREGATE_MAX_RESULT_BYTES.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private OperationResponse run(String json) {
        return processor.processMessage(RequestParser.parseRequest(json));
    }

    private void capAt(String size) throws Exception {
        TestUtils.setPrivateField(configuration, "aggregateMaxResultBytes", size);
    }

    private void uncapped() throws Exception {
        capAt(ConfigKey.AGGREGATE_MAX_RESULT_BYTES.defaultValue());
    }

    private static void assertOk(OperationResponse response) {
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
    }

    private static void assertRefused(OperationResponse response) {
        assertEquals(ErrorCode.AGGREGATE_RESULT_TOO_LARGE.getCode(), response.getErrorCode(), response.getMessage());
    }

    private static String write(String type, String collection, String payload) {
        return "{\"type\":\"" + type + "\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + collection + "\"," + payload + "}";
    }

    private static String aggregate(String collection, String steps) {
        return write("AGGREGATE", collection, "\"aggregationSteps\":[" + steps + "]");
    }

    private static String analyzed(String collection, String steps) {
        return write("AGGREGATE", collection, "\"analyze\":true,\"aggregationSteps\":[" + steps + "]");
    }

    private static List<JsonObject> results(OperationResponse response) {
        assertOk(response);
        return response instanceof AggregateAnalyzeResponse analyzed
                ? analyzed.getResults()
                : ((AggregateResponse) response).getResults();
    }

    private static long countOf(OperationResponse response) {
        return results(response).getFirst().get("count").asJsonNumber().getValue().longValue();
    }

    private static String groupBy(String field) {
        return "{\"type\":\"GROUP_BY\",\"fieldName\":\"" + field + "\"}";
    }

    private void index(String collection, String field) {
        assertOk(run(write("CREATE_INDEX", collection, "\"fieldName\":\"" + field + "\"")));
        IocContainer.get(PendingIndexWrites.class).clearCollection(TestGlobals.DB, collection);
    }

    @Test
    public void groupByOverBudgetAnswersResultTooLarge() throws Exception {
        final var steps = groupBy("n") + ",{\"type\":\"LIMIT\",\"limit\":1}";
        assertEquals(1, results(run(aggregate(TestGlobals.COLL, steps))).size());
        capAt(TIGHT);
        assertRefused(run(aggregate(TestGlobals.COLL, steps)));
        uncapped();
        index(TestGlobals.COLL, "n");
        assertEquals(1, results(run(aggregate(TestGlobals.COLL, steps))).size());
        capAt(TIGHT);
        assertRefused(run(aggregate(TestGlobals.COLL, steps)));
    }

    @Test
    public void groupByInsideATransactionUsesTheSameBudget() throws Exception {
        final var client = BeforeHookTestSupport.newClient();
        try {
            assertOk(processor.processMessage(new StartTransactionRequest(), client));
            assertOk(processor.processMessage(
                    RequestParser.parseRequest(write("SAVE", TestGlobals.COLL, "\"object\":{\"_id\":\"tx\",\"n\":-1}")),
                    client));
            capAt(TIGHT);
            final var steps = groupBy("n") + ",{\"type\":\"LIMIT\",\"limit\":1}";
            assertRefused(
                    processor.processMessage(RequestParser.parseRequest(aggregate(TestGlobals.COLL, steps)), client));
            assertEquals(DOCUMENTS + 1,
                    countOf(processor.processMessage(
                            RequestParser.parseRequest(aggregate(TestGlobals.COLL, groupBy("n") + "," + COUNT)),
                            client)));
        } finally {
            processor.processMessage(new RollbackTransactionRequest(), client);
            IocContainer.get(ClientTracker.class).removeById(client);
        }
    }

    @Test
    public void unboundedSortOverBudgetAnswersResultTooLarge() throws Exception {
        final var unbounded = "{\"type\":\"SORT\",\"fieldName\":\"n\",\"ascending\":true},"
                + "{\"type\":\"SKIP\",\"skip\":" + (DOCUMENTS - 1) + "}";
        assertEquals(1, results(run(aggregate(TestGlobals.COLL, unbounded))).size());
        capAt(TIGHT);
        assertRefused(run(aggregate(TestGlobals.COLL, unbounded)));
        final var bounded = "{\"type\":\"SORT\",\"fieldName\":\"n\",\"ascending\":false},"
                + "{\"type\":\"LIMIT\",\"limit\":1}";
        final var top = results(run(aggregate(TestGlobals.COLL, bounded)));
        assertEquals(DOCUMENTS - 1, top.getFirst().get("n").asJsonNumber().getValue().intValue());
    }

    @Test
    public void joinUnindexedRemoteKeepsOnlyMatchingRows() throws Exception {
        assertOk(run("{\"type\":\"CREATE_COLLECTION\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + REMOTE + "\"}"));
        final var remote = new StringBuilder(REMOTE_DOCUMENTS * 2_048);
        remote.append("{\"_id\":\"none\"},{\"_id\":\"nil\",\"key\":null},")
                .append("{\"_id\":\"num\",\"key\":1},{\"_id\":\"twin\",\"key\":\"g1\"}");
        for (var i = 0; i < REMOTE_DOCUMENTS; i++) {
            remote.append(",{\"_id\":\"r").append(i).append("\",\"key\":\"g").append(i).append("\",\"pad\":\"")
                    .repeat("y", 2_000).append("\"}");
        }
        assertOk(run(write("BULK_SAVE", REMOTE, "\"objects\":[" + remote + "]")));
        final var steps = "{\"type\":\"FILTER\",\"operator\":{\"fieldOperatorType\":\"SMALLER_THAN\",\"field\":\"n\","
                + "\"value\":3}},{\"type\":\"JOIN\",\"joinCollection\":\"" + REMOTE
                + "\",\"localField\":\"k\",\"remoteField\":\"key\",\"asField\":\"m\"}";
        capAt("64Kb");
        final var scanned = run(analyzed(TestGlobals.COLL, steps));
        final var rows = results(scanned);
        assertEquals(3, rows.size());
        assertEquals(DOCUMENTS + REMOTE_DOCUMENTS + 4,
                ((AggregateAnalyzeResponse) scanned).getAnalyzeResult().getDocumentsScanned());
        final var byId = rows.stream().collect(
                Collectors.toMap(row -> row.get("_id").asJsonString().getValue(), row -> row.get("m").toString()));
        assertEquals(List.of("r0"), joinedIds(rows, "d0"));
        assertEquals(List.of("r1", "twin"), joinedIds(rows, "d1"));
        assertEquals(List.of("r2"), joinedIds(rows, "d2"));
        uncapped();
        index(REMOTE, "key");
        capAt("64Kb");
        final var indexed = results(run(aggregate(TestGlobals.COLL, steps)));
        assertEquals(byId, indexed.stream().collect(
                Collectors.toMap(row -> row.get("_id").asJsonString().getValue(), row -> row.get("m").toString())));
    }

    private static List<String> joinedIds(List<JsonObject> rows, String id) {
        final var row = rows.stream().filter(candidate -> id.equals(candidate.get("_id").asJsonString().getValue()))
                .findFirst().orElseThrow();
        return row.get("m").asJsonArray().asList().stream()
                .map(joined -> joined.asJsonObject().get("_id").asJsonString().getValue()).toList();
    }

    @Test
    public void groupByThenCountDoesNotSortGroups() throws Exception {
        capAt(TIGHT);
        assertRefused(run(aggregate(TestGlobals.COLL, groupBy("n"))));
        assertEquals(DOCUMENTS, countOf(run(aggregate(TestGlobals.COLL, groupBy("n") + "," + COUNT))));
        assertEquals(4, countOf(run(aggregate(TestGlobals.COLL, groupBy("k") + "," + COUNT))));
        uncapped();
        index(TestGlobals.COLL, "n");
        capAt(TIGHT);
        final var counted = run(analyzed(TestGlobals.COLL, groupBy("n") + "," + COUNT));
        assertEquals(DOCUMENTS, countOf(counted));
        final var analysis = ((AggregateAnalyzeResponse) counted).getAnalyzeResult();
        assertEquals(0, analysis.getDocumentsScanned());
        assertTrue(analysis.getIndexesUsed().contains("n"), analysis.getIndexesUsed().toString());
    }

    @Test
    public void groupByThenCountAnswersTheGroupCountOnBothPaths() {
        final var mixed = "{\"_id\":\"m1\",\"v\":1},{\"_id\":\"m2\",\"v\":1.0},{\"_id\":\"m3\",\"v\":\"1\"},"
                + "{\"_id\":\"m4\",\"v\":\"A\"},{\"_id\":\"m5\",\"v\":\"a\"},{\"_id\":\"m6\",\"v\":true},"
                + "{\"_id\":\"m7\",\"v\":{\"x\":1}},{\"_id\":\"m8\",\"v\":{\"x\":1}},{\"_id\":\"m9\",\"v\":[1,2]},"
                + "{\"_id\":\"m10\",\"v\":\"A\"}";
        assertOk(run("{\"type\":\"CREATE_COLLECTION\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + MIXED + "\"}"));
        assertOk(run(write("BULK_SAVE", MIXED, "\"objects\":[" + mixed + "]")));
        final var scanFirst = "{\"type\":\"SKIP\",\"skip\":0}," + groupBy("v");
        final var scanGroups = results(run(aggregate(MIXED, scanFirst))).size();
        assertEquals(scanGroups, countOf(run(aggregate(MIXED, scanFirst + "," + COUNT))));
        assertEquals(scanGroups, countOf(run(aggregate(MIXED, groupBy("v") + "," + COUNT))));
        index(MIXED, "v");
        final var indexed = run(analyzed(MIXED, groupBy("v")));
        final var used = ((AggregateAnalyzeResponse) indexed).getAnalyzeResult();
        assertTrue(used.getIndexesUsed().contains("v"), used.getIndexesUsed() + " " + used.getDocumentsScanned());
        final var indexGroups = results(indexed).size();
        assertEquals(indexGroups, countOf(run(aggregate(MIXED, groupBy("v") + "," + COUNT))));
        assertEquals(scanGroups, indexGroups);
    }
}
