package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexSaltedKeyTest {
    private static final String FIELD = "k";
    private static final int COLLIDING_BLOCKS = 13;
    private static final int STORED = 4096;
    private static final int PENDING = 1366;
    private Cache cache;
    private PendingIndexWrites pending;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cache = IocContainer.get(Cache.class);
        pending = IocContainer.get(PendingIndexWrites.class);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static List<String> collidingStrings(int blocks) {
        var strings = List.of("");
        for (var block = 0; block < blocks; block++) {
            final var longer = new ArrayList<String>(strings.size() * 2);
            for (final var prefix : strings) {
                longer.add(prefix + "Aa");
                longer.add(prefix + "BB");
            }
            strings = longer;
        }
        return strings;
    }

    private static JsonObject collidingObject(String key) {
        final var object = new JsonObject();
        object.addProperty(key, 1);
        return object;
    }

    private void addDoc(String id, JsonBaseElement value) throws IOException {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add(FIELD, value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void addPendingDoc(String id, JsonBaseElement value) throws IOException {
        addDoc(id, value);
        pending.mark(TestGlobals.DB, TestGlobals.COLL, id);
    }

    private void enableIndex() throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(FIELD));
    }

    private Map<JsonBaseElement, Integer> groupSizes(List<BaseAggregationStep> steps) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(steps);
        return AggregationOperationHelper.processAggregation(request).stream()
                .collect(Collectors.toMap(group -> group.get(FIELD), group -> group.get("group").asJsonArray().size()));
    }

    private Map<JsonBaseElement, Integer> groupedByIndex() throws IOException {
        return groupSizes(List.of(new GroupByAggregationStep(FIELD)));
    }

    private Map<JsonBaseElement, Integer> groupedByScan() throws IOException {
        return groupSizes(List.of(new SkipAggregationStep(0), new GroupByAggregationStep(FIELD)));
    }

    private Set<String> idsEqualTo(JsonBaseElement value) throws IOException {
        final var operator = new FieldOperator(FieldOperatorType.EQUALS, FIELD, value);
        return FilterOperatorHelper.processOperator(operator, null, TestGlobals.DB, TestGlobals.COLL)
                .map(object -> object.get(Globals.PK_FIELD).asJsonString().getValue()).collect(Collectors.toSet());
    }

    @Test
    public void reconcileGroupsCollidingStringsExactly() throws Exception {
        final var values = collidingStrings(COLLIDING_BLOCKS);
        for (var i = 0; i < STORED; i++) {
            addDoc("s" + i, new JsonString(values.get(i)));
        }
        enableIndex();
        for (var i = 0; i < PENDING; i++) {
            addPendingDoc("p" + i, new JsonString(values.get(STORED + i)));
        }

        final var byIndex = groupedByIndex();

        assertEquals(STORED + PENDING, byIndex.size());
        assertEquals(Set.of(1), Set.copyOf(byIndex.values()));
        assertEquals(groupedByScan(), byIndex);
    }

    @Test
    public void hashIndexGroupsCollidingObjectsExactly() throws Exception {
        final var keys = collidingStrings(10);
        for (var i = 0; i < keys.size(); i++) {
            addDoc("o" + i, collidingObject(keys.get(i)));
        }
        enableIndex();

        final var byIndex = groupedByIndex();

        assertEquals(keys.size(), byIndex.size());
        assertEquals(groupedByScan(), byIndex);
    }

    @Test
    public void buildIndexKeepsEveryCollidingValueSeparate() throws Exception {
        final var values = collidingStrings(10);
        for (var i = 0; i < values.size(); i++) {
            addDoc("b" + i, new JsonString(values.get(i)));
        }

        enableIndex();

        for (var i = 0; i < values.size(); i += 31) {
            assertEquals(Set.of("b" + i), idsEqualTo(new JsonString(values.get(i))), "value " + values.get(i));
        }
    }

    @Test
    public void nullAndMissingStayDistinctUnderSalting() throws Exception {
        addDoc("1", new JsonString("x"));
        enableIndex();
        addPendingDoc("2", JsonNull.INSTANCE);
        addPendingDoc("3", JsonNull.INSTANCE);
        addPendingDoc("4", new JsonString("x"));

        final var byIndex = groupedByIndex();

        assertEquals(Map.of(JsonNull.INSTANCE, 2, new JsonString("x"), 2), byIndex);
        assertEquals(groupedByScan(), byIndex);
    }
}
