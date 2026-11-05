package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.test.TestGlobals;

final class TopKFixture {
    static final int DOCUMENTS = 3000;
    static final int REGIONS = 7;

    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final String collName;

    record Answer(List<String> ids, long scanned) {
    }

    TopKFixture(String collName) {
        this.collName = collName;
    }

    void createIndexedOnTs(List<JsonObject> documents) throws IOException, InterruptedException {
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, collName));
        final var bulk = new BulkSaveRequest(TestGlobals.DB, collName);
        bulk.setObjects(documents);
        assertEquals(OperationStatus.OK, processor.processMessage(bulk).getStatus());
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, collName, "ts"));
        drain();
    }

    void drop() {
        processor.processMessage(new DropCollectionRequest(TestGlobals.DB, collName));
    }

    static List<JsonObject> standardDocuments() {
        final var documents = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            documents.add(document(String.format("d%04d", i), i % REGIONS, (i * 7919) % DOCUMENTS));
        }
        return documents;
    }

    static JsonObject document(String id, int region, int ts) {
        final var document = new JsonObject();
        document.add(Globals.PK_FIELD, new JsonString(id));
        document.add("region", new JsonString("r" + region));
        document.add("flag", new JsonString(region % 2 == 0 ? "even" : "odd"));
        document.add("ts", new JsonNumber(ts));
        return document;
    }

    void drain() throws IOException, InterruptedException {
        final var pending = IocContainer.get(PendingIndexWrites.class);
        for (final var id : pending.idsFor(TestGlobals.DB, collName)) {
            final var entries = IocContainer.get(Cache.class).getEntriesByIds(TestGlobals.DB, collName, Set.of(id));
            if (!entries.isEmpty()) {
                EventProcessorHelper
                        .processEvent(new EntityEvent(EventType.CREATED, TestGlobals.DB, collName, entries.getFirst()));
            }
        }
        assertTrue(pending.idsFor(TestGlobals.DB, collName).isEmpty(), "the pending overlay must be drained");
    }

    void save(JsonObject document) {
        final var request = new SaveRequest(TestGlobals.DB, collName);
        request.setObject(document);
        final var response = processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
    }

    Answer run(BaseAggregationStep... steps) {
        final var request = new AggregateRequest(TestGlobals.DB, collName);
        request.setAnalyze(true);
        request.setAggregationSteps(List.of(steps));
        final var response = processor.processMessage(request);
        final var analyzed = assertInstanceOf(AggregateAnalyzeResponse.class, response, response.getMessage());
        return new Answer(
                analyzed.getResults().stream().map(row -> row.get(Globals.PK_FIELD).asJsonString().getValue()).toList(),
                analyzed.getAnalyzeResult().getDocumentsScanned());
    }

    List<String> scanned(BaseAggregationStep... steps) {
        final var prefixed = new ArrayList<BaseAggregationStep>();
        prefixed.add(new SkipAggregationStep(0));
        prefixed.addAll(List.of(steps));
        return run(prefixed.toArray(BaseAggregationStep[]::new)).ids();
    }

    static FilterAggregationStep regionEquals(String value) {
        return new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "region", new JsonString(value)));
    }

    static FieldOperator tsCompared(FieldOperatorType type, double value) {
        return new FieldOperator(type, "ts", new JsonNumber(value));
    }
}
