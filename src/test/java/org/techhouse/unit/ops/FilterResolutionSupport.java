package org.techhouse.unit.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.PipelineContext;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public abstract class FilterResolutionSupport {
    private static final EJson E_JSON = new EJson();
    protected Cache cache;

    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cache = IocContainer.get(Cache.class);
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    protected void addTyped(Cache target, String id, String field, JsonBaseElement value) throws IOException {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add(field, value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        TestUtils.cacheEntry(target, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    protected void insert(String id, String field, JsonBaseElement value) throws IOException {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        if (value != null) {
            object.add(field, value);
        }
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    protected void insert(String id) throws IOException {
        insert(id, null, null);
    }

    protected Cache mixedTypeFixture() {
        final var fixture = IocContainer.get(Cache.class);
        fixture.putAdminCollectionEntry(new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL),
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "seed", 0, 100, 0));
        return fixture;
    }

    protected void indexField(Cache target, String field) throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, field);
        target.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(field));
    }

    protected void indexField(String field) throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, field);
        final var entry = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        final var indexes = new HashSet<>(entry.getIndexes());
        indexes.add(field);
        entry.setIndexes(indexes);
    }

    protected Set<String> matched(FieldOperator op) throws IOException {
        return matched(op, PipelineContext.withoutScripts());
    }

    protected Set<String> matched(FieldOperator op, PipelineContext context) throws IOException {
        return FilterOperatorHelper.processOperator(op, null, TestGlobals.DB, TestGlobals.COLL, context)
                .map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).collect(Collectors.toSet());
    }

    protected static List<JsonObject> rows(BaseAggregationStep... steps) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(steps));
        return AggregationOperationHelper.processAggregation(request);
    }

    protected static List<String> idsInOrder(BaseAggregationStep... steps) throws IOException {
        return rows(steps).stream().map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).toList();
    }

    protected static List<String> ids(BaseAggregationStep... steps) throws IOException {
        return idsInOrder(steps).stream().sorted().toList();
    }

    protected static List<String> sortedJson(List<JsonObject> rows) {
        final var json = new ArrayList<String>();
        for (final var row : rows) {
            json.add(E_JSON.toJson(row));
        }
        json.sort(String::compareTo);
        return json;
    }

    protected static List<String> rowsJson(BaseAggregationStep... steps) throws IOException {
        return sortedJson(rows(steps));
    }

    protected static int countOf(BaseAggregationStep... steps) throws IOException {
        final var withCount = new ArrayList<>(List.of(steps));
        withCount.add(new CountAggregationStep());
        return rows(withCount.toArray(BaseAggregationStep[]::new)).getFirst().get("count").asJsonNumber().asInteger();
    }

    protected static FilterAggregationStep filter(FieldOperatorType type, String field, JsonBaseElement operand) {
        return new FilterAggregationStep(new FieldOperator(type, field, operand));
    }

    protected static JsonArray arrayOf(JsonBaseElement... values) {
        final var array = new JsonArray();
        for (final var value : values) {
            array.add(value);
        }
        return array;
    }
}
