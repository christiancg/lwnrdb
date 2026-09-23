package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.custom_types.JsonDateTime;
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
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * The property IndexScanAgreementTest cannot reach: a COUNT resolved by
 * CountOperatorHelper.tryIndexOnlyCount must equal the number of rows the same FILTER returns. That
 * fast path skips the document re-test FILTER performs, so an index answer that is a superset stops
 * being harmless there. For _id in particular there is no "before indexing" state to compare
 * against - the PK resolver always answers - so an agreement matrix proves nothing about it.
 */
public class FilterCountFastPathAgreementTest {
    private static final String FIELD = "score";
    private Cache cache;

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

    private void insert(String id, JsonBaseElement value) throws IOException {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        if (value != null) {
            object.add(FIELD, value);
        }
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void seed() throws IOException {
        insert("a", new JsonNumber(1));
        insert("b", new JsonNumber(2));
        insert("c", new JsonNumber(2));
    }

    private void indexField() throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        final var entry = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        final var indexes = new java.util.HashSet<>(entry.getIndexes());
        indexes.add(FIELD);
        entry.setIndexes(indexes);
    }

    private static FilterAggregationStep filter(FieldOperatorType type, String field, JsonBaseElement operand) {
        return new FilterAggregationStep(new FieldOperator(type, field, operand));
    }

    private static JsonArray arrayOf(JsonBaseElement... values) {
        final var array = new JsonArray();
        for (final var value : values) {
            array.add(value);
        }
        return array;
    }

    private int rowsOf(FilterAggregationStep step) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(step));
        return AggregationOperationHelper.processAggregation(request).size();
    }

    private int countOf(FilterAggregationStep step) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(step, new CountAggregationStep()));
        final var result = AggregationOperationHelper.processAggregation(request);
        return result.getFirst().get("count").asJsonNumber().asInteger();
    }

    private void assertCountMatchesRows(Map<String, FilterAggregationStep> queries) throws IOException {
        assertFalse(queries.isEmpty(), "the matrix must actually run something");
        for (final var query : queries.entrySet()) {
            assertEquals(rowsOf(query.getValue()), countOf(query.getValue()),
                    "COUNT disagrees with the rows the filter returns for: " + query.getKey());
        }
    }

    private static Map<String, FilterAggregationStep> operandMatrix(String field) {
        final var operands = new LinkedHashMap<String, JsonBaseElement>();
        operands.put("present string", new JsonString("a"));
        operands.put("absent string", new JsonString("zzz"));
        operands.put("number", new JsonNumber(5));
        operands.put("null", JsonNull.INSTANCE);
        operands.put("object", new JsonObject());
        operands.put("array", arrayOf());
        operands.put("custom", new JsonDateTime("#datetime(2024-01-01T10:00:00)"));
        final var queries = new LinkedHashMap<String, FilterAggregationStep>();
        for (final var operand : operands.entrySet()) {
            for (final var type : List.of(FieldOperatorType.EQUALS, FieldOperatorType.NOT_EQUALS,
                    FieldOperatorType.CONTAINS)) {
                queries.put(type + " " + field + " " + operand.getKey(), filter(type, field, operand.getValue()));
            }
        }
        for (final var type : List.of(FieldOperatorType.IN, FieldOperatorType.NOT_IN)) {
            queries.put(type + " " + field + " [a]", filter(type, field, arrayOf(new JsonString("a"))));
            queries.put(type + " " + field + " [5]", filter(type, field, arrayOf(new JsonNumber(5))));
            queries.put(type + " " + field + " [null]", filter(type, field, arrayOf(JsonNull.INSTANCE)));
        }
        return queries;
    }

    @Test
    public void test_count_after_an_id_filter_matches_the_row_count_for_every_operand_kind() throws Exception {
        seed();

        assertCountMatchesRows(operandMatrix(Globals.PK_FIELD));
    }

    @Test
    public void test_count_after_an_indexed_field_filter_matches_the_row_count() throws Exception {
        seed();
        indexField();

        assertCountMatchesRows(operandMatrix(FIELD));
    }

    @Test
    public void test_count_after_id_not_equals_a_number_matches_the_row_count() throws Exception {
        seed();
        final var step = filter(FieldOperatorType.NOT_EQUALS, Globals.PK_FIELD, new JsonNumber(5));

        assertEquals(0, rowsOf(step));
        assertEquals(0, countOf(step));
    }

    @Test
    public void test_count_after_id_not_equals_a_present_id_still_uses_the_pk_index() throws Exception {
        seed();
        final var step = filter(FieldOperatorType.NOT_EQUALS, Globals.PK_FIELD, new JsonString("b"));

        assertEquals(2, rowsOf(step));
        assertEquals(2, countOf(step));
    }

    @Test
    public void test_id_not_equals_null_names_every_document_because_no_id_is_null() throws Exception {
        seed();
        final var step = filter(FieldOperatorType.NOT_EQUALS, Globals.PK_FIELD, JsonNull.INSTANCE);

        assertEquals(3, rowsOf(step));
        assertEquals(3, countOf(step));
    }

    @Test
    public void test_count_after_id_not_in_a_non_string_list_still_returns_every_document() throws Exception {
        seed();
        final var step = filter(FieldOperatorType.NOT_IN, Globals.PK_FIELD,
                arrayOf(new JsonNumber(5), JsonNull.INSTANCE));

        assertEquals(3, rowsOf(step));
        assertEquals(3, countOf(step));
    }

    @Test
    public void test_an_empty_collection_counts_zero_for_an_uninterpretable_id_operand() throws Exception {
        final var step = filter(FieldOperatorType.NOT_EQUALS, Globals.PK_FIELD, new JsonNumber(5));

        assertEquals(0, rowsOf(step));
        assertEquals(0, countOf(step));
    }
}
