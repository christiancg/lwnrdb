package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexScanComplementAgreementTest {
    private static final String FIELD = "x";
    private Cache cache;
    private final org.techhouse.ejson.EJson eJson = new org.techhouse.ejson.EJson();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cache = IocContainer.get(Cache.class);
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void store(String id, JsonBaseElement value) throws IOException {
        final var document = new JsonObject();
        document.add(Globals.PK_FIELD, new JsonString(id));
        document.add(FIELD, value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, document);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void indexTheField() throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        final var collection = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        final var registered = new HashSet<>(collection.getIndexes());
        registered.add(FIELD);
        collection.setIndexes(registered);
    }

    private List<String> answer(BaseAggregationStep... steps) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(steps));
        final var rows = new ArrayList<String>();
        for (final var row : AggregationOperationHelper.processAggregation(request)) {
            rows.add(eJson.toJson(row));
        }
        rows.sort(String::compareTo);
        return rows;
    }

    private void assertIndexAgreesWithScan(BaseAggregationStep... steps) throws Exception {
        final var scanned = answer(steps);
        indexTheField();
        assertEquals(scanned, answer(steps));
    }

    private static FilterAggregationStep where(FieldOperatorType type, JsonBaseElement operand) {
        return new FilterAggregationStep(new FieldOperator(type, FIELD, operand));
    }

    private static JsonObject objectWithA(int a) {
        final var object = new JsonObject();
        object.add("a", new JsonNumber(a));
        return object;
    }

    private static JsonArray arrayOf(JsonBaseElement... elements) {
        final var array = new JsonArray();
        for (final var element : elements) {
            array.add(element);
        }
        return array;
    }

    private Set<String> idsFromIndex(FieldOperatorType type, JsonBaseElement operand) throws IOException {
        return cache.getIdsFromIndex(TestGlobals.DB, TestGlobals.COLL, FIELD, new FieldOperator(type, FIELD, operand),
                operand);
    }

    @Test
    public void test_not_equals_object_operand_agrees_across_container_and_scalar_values() throws Exception {
        store("1", objectWithA(1));
        store("2", arrayOf(new JsonNumber(1), new JsonNumber(2)));
        store("3", new JsonString("hello"));
        final var scanned = answer(where(FieldOperatorType.NOT_EQUALS, objectWithA(1)));
        assertEquals(2, scanned.size());
        assertIndexAgreesWithScan(where(FieldOperatorType.NOT_EQUALS, objectWithA(1)));
    }

    @Test
    public void test_not_equals_array_operand_agrees_across_container_and_scalar_values() throws Exception {
        store("1", arrayOf(new JsonNumber(1)));
        store("2", objectWithA(2));
        store("3", new JsonNumber(5));
        assertIndexAgreesWithScan(where(FieldOperatorType.NOT_EQUALS, arrayOf(new JsonNumber(1))));
    }

    @Test
    public void test_not_equals_object_operand_matches_an_explicit_null() throws Exception {
        store("1", objectWithA(1));
        store("2", objectWithA(2));
        store("3", JsonNull.INSTANCE);
        assertEquals(2, answer(where(FieldOperatorType.NOT_EQUALS, objectWithA(1))).size());
        assertIndexAgreesWithScan(where(FieldOperatorType.NOT_EQUALS, objectWithA(1)));
    }

    @Test
    public void test_not_in_matches_an_explicit_null_with_a_single_type_index() throws Exception {
        store("1", new JsonString("a"));
        store("2", new JsonString("b"));
        store("3", JsonNull.INSTANCE);
        assertEquals(2, answer(where(FieldOperatorType.NOT_IN, arrayOf(new JsonString("a")))).size());
        assertIndexAgreesWithScan(where(FieldOperatorType.NOT_IN, arrayOf(new JsonString("a"))));
    }

    @Test
    public void test_not_in_count_matches_filter_with_an_explicit_null() throws Exception {
        store("1", new JsonString("a"));
        store("2", new JsonString("b"));
        store("3", JsonNull.INSTANCE);
        assertIndexAgreesWithScan(where(FieldOperatorType.NOT_IN, arrayOf(new JsonString("a"))),
                new CountAggregationStep());
    }

    @Test
    public void test_not_in_object_operand_matches_an_explicit_null() throws Exception {
        store("1", objectWithA(1));
        store("2", objectWithA(2));
        store("3", JsonNull.INSTANCE);
        assertIndexAgreesWithScan(where(FieldOperatorType.NOT_IN, arrayOf(objectWithA(1))));
    }

    @Test
    public void test_complements_still_use_the_index_when_every_document_shares_one_kind() throws Exception {
        store("1", objectWithA(1));
        store("2", objectWithA(2));
        indexTheField();
        assertEquals(Set.of("2"), idsFromIndex(FieldOperatorType.NOT_EQUALS, objectWithA(1)));
        assertEquals(Set.of("2"), idsFromIndex(FieldOperatorType.NOT_IN, arrayOf(objectWithA(1))));
    }

    @Test
    public void test_scalar_not_in_still_uses_the_index_when_every_document_is_covered() throws Exception {
        store("1", new JsonString("a"));
        store("2", new JsonString("b"));
        indexTheField();
        assertNotNull(idsFromIndex(FieldOperatorType.NOT_IN, arrayOf(new JsonString("a"))));
    }

    @Test
    public void test_complements_decline_the_index_when_a_document_is_not_covered() throws Exception {
        store("1", objectWithA(1));
        store("2", JsonNull.INSTANCE);
        indexTheField();
        assertNull(idsFromIndex(FieldOperatorType.NOT_EQUALS, objectWithA(1)));
        assertNull(idsFromIndex(FieldOperatorType.NOT_IN, arrayOf(objectWithA(1))));
        assertNotNull(idsFromIndex(FieldOperatorType.EQUALS, objectWithA(1)));
    }

    @Test
    public void test_array_not_equals_declines_the_index_when_another_kind_is_indexed() throws Exception {
        store("1", arrayOf(new JsonNumber(1)));
        store("2", new JsonString("s"));
        indexTheField();
        assertNull(idsFromIndex(FieldOperatorType.NOT_EQUALS, arrayOf(new JsonNumber(1))));
        assertNotNull(idsFromIndex(FieldOperatorType.EQUALS, arrayOf(new JsonNumber(1))));
    }
}
