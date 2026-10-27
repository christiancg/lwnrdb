package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.RollbackTransactionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class JoinOverlayTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private UUID client;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();
        for (final var key : List.of("k1", "k2", "k3")) {
            save(TestGlobals.COLL, document("left-" + key, "k", key), null);
        }
        save(TestGlobals.JOIN_COLL, document("r-a", "k", "k1"), null);
        save(TestGlobals.JOIN_COLL, document("r-b", "k", "k1"), null);
        save(TestGlobals.JOIN_COLL, document("r-c", "k", "k2"), null);
        client = BeforeHookTestSupport.newClient();
    }

    @AfterEach
    public void tearDown() throws Exception {
        processor.processMessage(new RollbackTransactionRequest(), client);
        clientTracker.removeById(client);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id, String field, String value) {
        final var document = new JsonObject();
        document.add("_id", new JsonString(id));
        document.add(field, new JsonString(value));
        return document;
    }

    private void save(String collName, JsonObject document, UUID clientId) {
        final var request = new SaveRequest(TestGlobals.DB, collName);
        request.setObject(document);
        final var response = clientId == null
                ? processor.processMessage(request)
                : processor.processMessage(request, clientId);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
    }

    private void bufferedChanges() {
        assertEquals(OperationStatus.OK, processor.processMessage(new StartTransactionRequest(), client).getStatus());
        save(TestGlobals.JOIN_COLL, document("r-new", "k", "k3"), client);
        save(TestGlobals.JOIN_COLL, document("r-b", "k", "k2"), client);
        final var delete = new DeleteRequest(TestGlobals.DB, TestGlobals.JOIN_COLL);
        delete.set_id("r-c");
        assertEquals(OperationStatus.OK, processor.processMessage(delete, client).getStatus());
    }

    private AggregateAnalyzeResponse join(String localField, String remoteField) {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAnalyze(true);
        request.setAggregationSteps(
                List.of(new JoinAggregationStep(TestGlobals.JOIN_COLL, localField, remoteField, "joined")));
        return assertInstanceOf(AggregateAnalyzeResponse.class, processor.processMessage(request, client));
    }

    private static Map<String, List<String>> joinedIds(AggregateAnalyzeResponse response) {
        final var joined = new TreeMap<String, List<String>>();
        for (final var row : response.getResults()) {
            final var ids = !row.has("joined") || !row.get("joined").isJsonArray()
                    ? List.<String>of()
                    : row.get("joined").asJsonArray().asList().stream()
                            .map(element -> element.asJsonObject().get("_id").asJsonString().getValue()).toList();
            joined.put(row.get("_id").asJsonString().getValue(), ids);
        }
        return joined;
    }

    @Test
    public void test_an_indexed_join_inside_a_transaction_sees_buffered_remote_writes() {
        assertEquals(OperationStatus.OK, processor
                .processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.JOIN_COLL, "k")).getStatus());
        bufferedChanges();

        final var response = join("k", "k");

        assertTrue(response.getAnalyzeResult().getIndexesUsed().contains("k"),
                "the remote index still answers inside the transaction");
        assertEquals(Map.of("left-k1", List.of("r-a"), "left-k2", List.of("r-b"), "left-k3", List.of("r-new")),
                joinedIds(response));
    }

    @Test
    public void test_an_id_join_inside_a_transaction_sees_buffered_inserts_and_deletes() {
        save(TestGlobals.COLL, document("left-new", "ref", "r-new"), null);
        save(TestGlobals.COLL, document("left-c", "ref", "r-c"), null);
        save(TestGlobals.COLL, document("left-a", "ref", "r-a"), null);
        bufferedChanges();

        final var response = join("ref", "_id");

        final var joined = joinedIds(response);
        assertEquals(List.of("r-new"), joined.get("left-new"), "a buffered insert joins by its id");
        assertEquals(List.of(), joined.get("left-c"), "a buffered delete no longer joins");
        assertEquals(List.of("r-a"), joined.get("left-a"));
    }
}
