package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AggregationGroupingStepTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // Process group by operation with valid field name and data
    @Test
    public void test_process_group_by_operation_with_valid_field() throws IOException {
        // Arrange
        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        GroupByAggregationStep groupByStep = new GroupByAggregationStep("groupField");
        request.setAggregationSteps(List.of(groupByStep));

        JsonObject jsonObject1 = new JsonObject();
        jsonObject1.addProperty("groupField", "value1");
        JsonObject jsonObject2 = new JsonObject();
        jsonObject2.addProperty("groupField", "value1");

        // Act
        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        // Assert
        assertEquals(0, result.size());
    }

    // Helper to insert entries directly into cache and page metadata for the test collection
    private void insertEntry(Cache cache, String id, Object fieldValue) {
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        if (fieldValue instanceof String s)
            obj.addProperty("type", s);
        else if (fieldValue instanceof Number n)
            obj.addProperty("type", n);
        DbEntry entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, 100);
    }

    // GROUP_BY groups documents by a given field value
    @Test
    public void test_group_by_groups_documents_by_field() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        insertEntry(cache, "g1", "A");
        insertEntry(cache, "g2", "B");
        insertEntry(cache, "g3", "A");

        AggregateRequest request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new GroupByAggregationStep("type")));

        List<JsonObject> result = AggregationOperationHelper.processAggregation(request);

        assertEquals(2, result.size());
    }

    // ---- Index-backed aggregation steps (GROUP_BY, JOIN, SORT, DISTINCT) ----

    private void addDoc(Cache cache, String id, String field, JsonBaseElement value) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add(field, value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void enableIndex(Cache cache, String field) {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, field);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(field));
    }

    // GROUP_BY over an indexed field produces the same groups as a non-indexed scan
    @Test
    public void test_group_by_uses_index_groups_match_scan() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "g1", "type", new JsonString("A"));
        addDoc(cache, "g2", "type", new JsonString("B"));
        addDoc(cache, "g3", "type", new JsonString("A"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new GroupByAggregationStep("type")));
        final var scan = AggregationOperationHelper.processAggregation(req);

        enableIndex(cache, "type");
        final var indexed = AggregationOperationHelper.processAggregation(req);

        assertEquals(scan.size(), indexed.size());
        assertEquals(2, indexed.size());
        final var groupA = indexed.stream().filter(o -> o.get("type").asJsonString().getValue().equals("A")).findFirst()
                .orElseThrow();
        assertEquals(2, groupA.get("group").asJsonArray().size());
        final var groupB = indexed.stream().filter(o -> o.get("type").asJsonString().getValue().equals("B")).findFirst()
                .orElseThrow();
        assertEquals(1, groupB.get("group").asJsonArray().size());
    }

    // GROUP_BY after a FILTER must operate on the filtered subset (no index fast-path)
    @Test
    public void test_group_by_with_upstream_filter_does_not_use_index() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "g1", "type", new JsonString("A"));
        addDoc(cache, "g2", "type", new JsonString("B"));
        addDoc(cache, "g3", "type", new JsonString("A"));
        enableIndex(cache, "type");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(
                new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "type", new JsonString("A"))),
                new GroupByAggregationStep("type")));
        final var result = AggregationOperationHelper.processAggregation(req);

        assertEquals(1, result.size());
        assertEquals("A", result.getFirst().get("type").asJsonString().getValue());
        assertEquals(2, result.getFirst().get("group").asJsonArray().size());
    }

    // GROUP_BY on a mixed scalar+object indexed field includes object-valued docs in the result
    @Test
    public void test_group_by_mixed_type_indexed_field_includes_object_valued_docs() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        addDoc(cache, "s1", "meta", new JsonString("plain"));
        addDoc(cache, "s2", "meta", new JsonString("plain"));
        final var obj = new JsonObject();
        obj.addProperty("key", "val");
        addDoc(cache, "o1", "meta", obj);
        enableIndex(cache, "meta");

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new GroupByAggregationStep("meta")));
        final var result = AggregationOperationHelper.processAggregation(req);

        final var allDocIds = new java.util.HashSet<String>();
        for (var g : result) {
            final var group = g.get("group");
            if (group != null && group.isJsonArray()) {
                for (var el : group.asJsonArray()) {
                    allDocIds.add(el.asJsonObject().get(Globals.PK_FIELD).asJsonString().getValue());
                }
            }
        }
        assertEquals(Set.of("s1", "s2", "o1"), allDocIds);
    }
}
