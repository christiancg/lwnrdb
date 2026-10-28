package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.Transaction;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
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
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.ReduceAggregationStep;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.tx.OverlayRead;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OverlayReadTest {
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private UUID client;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        for (var i = 0; i < 20; i++) {
            final var document = document("d" + i, i % 4);
            document.add("embedding", new JsonVector("#vector(" + (10.0 - i) + "," + (double) i + ")"));
            save(document, null);
        }
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "grp")).getStatus());
        client = BeforeHookTestSupport.newClient();
    }

    @AfterEach
    public void tearDown() throws Exception {
        processor.processMessage(new RollbackTransactionRequest(), client);
        clientTracker.removeById(client);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id, int group) {
        final var document = new JsonObject();
        document.add("_id", new JsonString(id));
        document.add("grp", new JsonNumber(group));
        return document;
    }

    private void save(JsonObject document, UUID clientId) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document);
        final var response = clientId == null
                ? processor.processMessage(request)
                : processor.processMessage(request, clientId);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
    }

    private void delete(String id) {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        assertEquals(OperationStatus.OK, processor.processMessage(request, client).getStatus());
    }

    private void start() {
        assertEquals(OperationStatus.OK, processor.processMessage(new StartTransactionRequest(), client).getStatus());
    }

    private OperationResponse run(boolean inTransaction, boolean analyze, BaseAggregationStep... steps) {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAnalyze(analyze);
        request.setAggregationSteps(List.of(steps));
        return inTransaction ? processor.processMessage(request, client) : processor.processMessage(request);
    }

    private List<JsonObject> aggregate(boolean inTransaction, BaseAggregationStep... steps) {
        final var response = run(inTransaction, false, steps);
        return assertInstanceOf(AggregateResponse.class, response, response.getMessage()).getResults();
    }

    private static List<String> ids(List<JsonObject> results) {
        return results.stream().map(document -> document.get("_id").asJsonString().getValue()).toList();
    }

    private static FilterAggregationStep groupIs(int group) {
        return new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "grp", new JsonNumber(group)));
    }

    @Test
    public void test_an_indexed_filter_inside_a_transaction_uses_the_index_and_sees_buffered_writes() {
        final var committed = ids(aggregate(false, groupIs(1)));
        start();
        save(document("new1", 1), client);
        final var stillIn = document("d1", 1);
        stillIn.add("marker", new JsonString("buffered"));
        save(stillIn, client);
        save(document("d5", 2), client);
        save(document("d2", 1), client);
        delete("d9");

        final var response = assertInstanceOf(AggregateAnalyzeResponse.class, run(true, true, groupIs(1)));

        assertTrue(response.getAnalyzeResult().getIndexesUsed().contains("grp"),
                "the FILTER source answers through the index inside the transaction");
        final var expected = new ArrayList<>(committed);
        expected.removeAll(List.of("d5", "d9"));
        expected.addAll(List.of("new1", "d2"));
        assertEquals(expected, ids(response.getResults()));
        final var updated = response.getResults().get(ids(response.getResults()).indexOf("d1"));
        assertEquals("buffered", updated.get("marker").asJsonString().getValue());
    }

    @Test
    public void test_the_merge_stops_at_the_limit() {
        final var overlay = new LinkedHashMap<String, JsonObject>();
        overlay.put("c1", document("c1", 9));
        overlay.put("tail", document("tail", 9));
        final var pulled = new AtomicInteger();
        final var tested = new AtomicInteger();
        final var committed = Stream.of("c0", "c1", "c2", "c3").map(id -> document(id, 0))
                .peek(_ -> pulled.incrementAndGet());

        final var merged = OverlayRead.reconcile(overlay, committed, _ -> tested.incrementAndGet() > 0).limit(2)
                .toList();

        assertEquals(List.of("c0", "c1"), merged.stream().map(d -> d.get("_id").asJsonString().getValue()).toList());
        assertSame(overlay.get("c1"), merged.get(1), "the buffered copy replaces the committed one in place");
        assertEquals(2, pulled.get(), "the committed source is read only as far as the LIMIT");
        assertEquals(1, tested.get(), "the buffered tail is never reached");
    }

    @Test
    public void test_reconcile_without_overlay_returns_committed() {
        final var committed = Stream.of(document("x", 1));

        assertSame(committed, OverlayRead.reconcile(null, committed, _ -> true));
        assertSame(committed, OverlayRead.reconcile(Map.of(), committed, _ -> true));
        final var matches = List.of(document("y", 1));
        assertSame(matches, OverlayRead.withBuffered(null, matches));
    }

    @Test
    public void test_a_nearest_filter_inside_a_transaction_ranks_the_merged_collection() {
        start();
        final var nearest = document("nearest", 0);
        nearest.add("embedding", new JsonVector("#vector(10.0,0.1)"));
        save(nearest, client);
        final var movedAway = document("d1", 1);
        movedAway.add("embedding", new JsonVector("#vector(0.0,10.0)"));
        save(movedAway, client);
        final var args = new JsonObject();
        final var query = new JsonVector("#vector(10.0,0.0)");
        args.add("value", query);
        args.add("k", new JsonNumber(3));

        final var response = aggregate(true,
                new FilterAggregationStep(new CustomOperator("nearest", "embedding", query, args)));

        assertEquals(List.of("d0", "nearest", "d2"), ids(response));
    }

    @Test
    public void test_an_update_that_still_matches_keeps_its_committed_position() {
        final var committed = ids(aggregate(false, groupIs(2)));
        start();
        final var update = document("d6", 2);
        update.add("marker", new JsonString("buffered"));
        save(update, client);

        final var inside = aggregate(true, groupIs(2), new LimitAggregationStep(10));

        assertEquals(committed, ids(inside));
        assertEquals("buffered", inside.get(committed.indexOf("d6")).get("marker").asJsonString().getValue());
    }

    @Test
    public void test_reduce_after_filter_folds_in_id_order() {
        start();
        save(document("a-first", 3), client);
        save(document("z-last", 3), client);
        delete("d3");
        final var fold = new ReduceAggregationStep("export default (acc, doc) => acc + '|' + doc._id;",
                new JsonString(""), "folded");

        final var response = aggregate(true, groupIs(3), fold);

        final JsonBaseElement folded = response.getFirst().get("folded");
        assertEquals("|a-first|d11|d15|d19|d7|z-last", folded.asJsonString().getValue());
    }

    @Test
    public void test_with_buffered_replaces_committed_matches_and_adds_the_rest() {
        final var overlay = new LinkedHashMap<String, JsonObject>();
        overlay.put("a", document("a", 2));
        overlay.put("gone", tombstoneOf());
        overlay.put("b", document("b", 3));

        final var merged = OverlayRead.withBuffered(overlay,
                List.of(document("a", 1), document("gone", 1), document("kept", 1)));

        assertEquals(List.of("kept", "a", "b"),
                merged.stream().map(d -> d.get("_id").asJsonString().getValue()).toList());
    }

    private JsonObject tombstoneOf() {
        final var transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());
        transaction.recordDelete("c", "gone", 1L);
        return transaction.overlayFor("c").get("gone");
    }
}
