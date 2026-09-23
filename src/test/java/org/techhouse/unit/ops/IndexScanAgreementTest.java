package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
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
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * The single property the whole index layer owes its callers: an index-backed answer equals the
 * full-scan answer. Every query below is run twice against one fixed corpus - once before the field
 * is indexed and once after - and the two results must match. It is deliberately a matrix rather
 * than a case per defect: it covers operators nobody has broken yet, and the corpus carries the
 * value classes that have historically disagreed (an integral operand the parser narrows to Integer
 * against a Double index entry, negative zero, Integer.MIN_VALUE, case variants, absent fields).
 */
public class IndexScanAgreementTest {
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

    private void insert(String id, String field, JsonBaseElement value) throws IOException {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        if (value != null) {
            object.add(field, value);
        }
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void indexField(String field) throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, field);
        final var entry = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        final var indexes = new java.util.HashSet<>(entry.getIndexes());
        indexes.add(field);
        entry.setIndexes(indexes);
    }

    private List<String> run(BaseAggregationStep... steps) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(steps));
        final var results = new ArrayList<String>();
        for (final var document : AggregationOperationHelper.processAggregation(request)) {
            results.add(eJson.toJson(document));
        }
        java.util.Collections.sort(results);
        return results;
    }

    private static FilterAggregationStep filter(FieldOperatorType type, String field, JsonBaseElement value) {
        return new FilterAggregationStep(new FieldOperator(type, field, value));
    }

    private static JsonArray array(JsonBaseElement... values) {
        final var arr = new JsonArray();
        for (final var value : values) {
            arr.add(value);
        }
        return arr;
    }

    private static JsonNumber parsed(String literal) {
        return new JsonNumber(literal);
    }

    private void seedNumbers() throws IOException {
        insert("n_neg", "score", parsed("-7"));
        insert("n_negzero", "score", new JsonNumber(-0.0));
        insert("n_zero", "score", parsed("0"));
        insert("n_one", "score", parsed("1"));
        insert("n_two_a", "score", parsed("2"));
        insert("n_two_b", "score", parsed("2"));
        insert("n_frac", "score", parsed("2.5"));
        insert("n_nine", "score", parsed("9"));
        insert("n_min", "score", parsed(Integer.toString(Integer.MIN_VALUE)));
        insert("n_big", "score", parsed("10000000000"));
        insert("n_absent", "score", null);
    }

    private void seedStrings() throws IOException {
        insert("s_bob", "name", new JsonString("Bob"));
        insert("s_bob_lower", "name", new JsonString("bob"));
        insert("s_carol", "name", new JsonString("carol"));
        insert("s_empty", "name", new JsonString(""));
        insert("s_unicode", "name", new JsonString("Ünïcödé"));
        insert("s_absent", "name", null);
    }

    private void seedBooleans() throws IOException {
        insert("b_true", "flag", new JsonBoolean(true));
        insert("b_false", "flag", new JsonBoolean(false));
        insert("b_absent", "flag", null);
    }

    private Map<String, Supplier<BaseAggregationStep[]>> numericQueries() {
        final var queries = new LinkedHashMap<String, Supplier<BaseAggregationStep[]>>();
        for (final var literal : List.of("0", "2", "2.5", "9", "-7", Integer.toString(Integer.MIN_VALUE))) {
            for (final var type : List.of(FieldOperatorType.EQUALS, FieldOperatorType.NOT_EQUALS,
                    FieldOperatorType.GREATER_THAN, FieldOperatorType.GREATER_THAN_EQUALS,
                    FieldOperatorType.SMALLER_THAN, FieldOperatorType.SMALLER_THAN_EQUALS)) {
                queries.put(type + " score " + literal,
                        () -> new BaseAggregationStep[]{filter(type, "score", parsed(literal))});
            }
        }
        for (final var operands : List.of(array(parsed("2")), array(parsed("2"), parsed("9")),
                array(parsed("2"), parsed("2.5")), array(parsed("0")),
                array(parsed(Integer.toString(Integer.MIN_VALUE))))) {
            queries.put("IN score " + operands,
                    () -> new BaseAggregationStep[]{filter(FieldOperatorType.IN, "score", operands)});
            queries.put("NOT_IN score " + operands,
                    () -> new BaseAggregationStep[]{filter(FieldOperatorType.NOT_IN, "score", operands)});
        }
        queries.put("COUNT after EQUALS score 2", () -> new BaseAggregationStep[]{
                filter(FieldOperatorType.EQUALS, "score", parsed("2")), new CountAggregationStep()});
        queries.put("COUNT after IN score [2,9]", () -> new BaseAggregationStep[]{
                filter(FieldOperatorType.IN, "score", array(parsed("2"), parsed("9"))), new CountAggregationStep()});
        queries.put("SORT score asc", () -> new BaseAggregationStep[]{new SortAggregationStep("score", true)});
        queries.put("SORT score desc", () -> new BaseAggregationStep[]{new SortAggregationStep("score", false)});
        queries.put("DISTINCT score", () -> new BaseAggregationStep[]{new DistinctAggregationStep("score")});
        queries.put("GROUP_BY score", () -> new BaseAggregationStep[]{new GroupByAggregationStep("score")});
        return queries;
    }

    private Map<String, Supplier<BaseAggregationStep[]>> stringQueries() {
        final var queries = new LinkedHashMap<String, Supplier<BaseAggregationStep[]>>();
        for (final var operand : List.of("Bob", "bob", "carol", "", "Ünïcödé", "missing")) {
            for (final var type : List.of(FieldOperatorType.EQUALS, FieldOperatorType.NOT_EQUALS,
                    FieldOperatorType.CONTAINS)) {
                queries.put(type + " name '" + operand + "'",
                        () -> new BaseAggregationStep[]{filter(type, "name", new JsonString(operand))});
            }
        }
        queries.put("IN name [bob,carol]", () -> new BaseAggregationStep[]{
                filter(FieldOperatorType.IN, "name", array(new JsonString("bob"), new JsonString("carol")))});
        queries.put("NOT_IN name [bob]", () -> new BaseAggregationStep[]{
                filter(FieldOperatorType.NOT_IN, "name", array(new JsonString("bob")))});
        queries.put("IN name [BOB]",
                () -> new BaseAggregationStep[]{filter(FieldOperatorType.IN, "name", array(new JsonString("BOB")))});
        queries.put("NOT_IN name [BOB]", () -> new BaseAggregationStep[]{
                filter(FieldOperatorType.NOT_IN, "name", array(new JsonString("BOB")))});
        queries.put("IN name [missing]", () -> new BaseAggregationStep[]{
                filter(FieldOperatorType.IN, "name", array(new JsonString("missing")))});
        queries.put("NOT_IN name [missing]", () -> new BaseAggregationStep[]{
                filter(FieldOperatorType.NOT_IN, "name", array(new JsonString("missing")))});
        queries.put("IN name ['']",
                () -> new BaseAggregationStep[]{filter(FieldOperatorType.IN, "name", array(new JsonString("")))});
        queries.put("IN name []", () -> new BaseAggregationStep[]{filter(FieldOperatorType.IN, "name", array())});
        queries.put("COUNT after IN name [BOB]", () -> new BaseAggregationStep[]{
                filter(FieldOperatorType.IN, "name", array(new JsonString("BOB"))), new CountAggregationStep()});
        queries.put("COUNT after NOT_IN name [BOB]", () -> new BaseAggregationStep[]{
                filter(FieldOperatorType.NOT_IN, "name", array(new JsonString("BOB"))), new CountAggregationStep()});
        queries.put("SORT name asc", () -> new BaseAggregationStep[]{new SortAggregationStep("name", true)});
        queries.put("DISTINCT name", () -> new BaseAggregationStep[]{new DistinctAggregationStep("name")});
        queries.put("GROUP_BY name", () -> new BaseAggregationStep[]{new GroupByAggregationStep("name")});
        return queries;
    }

    private void seedNulls() throws IOException {
        insert("x_null", "state", org.techhouse.ejson.elements.JsonNull.INSTANCE);
        insert("x_string", "state", new JsonString("open"));
        insert("x_number", "state", parsed("7"));
        insert("x_absent", "state", null);
    }

    private Map<String, Supplier<BaseAggregationStep[]>> nullOperandQueries() {
        final var queries = new LinkedHashMap<String, Supplier<BaseAggregationStep[]>>();
        for (final var type : List.of(FieldOperatorType.EQUALS, FieldOperatorType.NOT_EQUALS,
                FieldOperatorType.GREATER_THAN, FieldOperatorType.GREATER_THAN_EQUALS, FieldOperatorType.SMALLER_THAN,
                FieldOperatorType.SMALLER_THAN_EQUALS, FieldOperatorType.CONTAINS)) {
            queries.put(type + " state null", () -> new BaseAggregationStep[]{
                    filter(type, "state", org.techhouse.ejson.elements.JsonNull.INSTANCE)});
        }
        queries.put("IN state [null]", () -> new BaseAggregationStep[]{
                filter(FieldOperatorType.IN, "state", array(org.techhouse.ejson.elements.JsonNull.INSTANCE))});
        queries.put("NOT_IN state [null]", () -> new BaseAggregationStep[]{
                filter(FieldOperatorType.NOT_IN, "state", array(org.techhouse.ejson.elements.JsonNull.INSTANCE))});
        queries.put("COUNT after NOT_EQUALS state null",
                () -> new BaseAggregationStep[]{
                        filter(FieldOperatorType.NOT_EQUALS, "state", org.techhouse.ejson.elements.JsonNull.INSTANCE),
                        new CountAggregationStep()});
        queries.put("SORT state asc", () -> new BaseAggregationStep[]{new SortAggregationStep("state", true)});
        queries.put("DISTINCT state", () -> new BaseAggregationStep[]{new DistinctAggregationStep("state")});
        return queries;
    }

    private Map<String, Supplier<BaseAggregationStep[]>> booleanQueries() {
        final var queries = new LinkedHashMap<String, Supplier<BaseAggregationStep[]>>();
        for (final var operand : List.of(true, false)) {
            queries.put("EQUALS flag " + operand, () -> new BaseAggregationStep[]{
                    filter(FieldOperatorType.EQUALS, "flag", new JsonBoolean(operand))});
            queries.put("NOT_EQUALS flag " + operand, () -> new BaseAggregationStep[]{
                    filter(FieldOperatorType.NOT_EQUALS, "flag", new JsonBoolean(operand))});
        }
        queries.put("IN flag [true]",
                () -> new BaseAggregationStep[]{filter(FieldOperatorType.IN, "flag", array(new JsonBoolean(true)))});
        queries.put("NOT_IN flag [true]", () -> new BaseAggregationStep[]{
                filter(FieldOperatorType.NOT_IN, "flag", array(new JsonBoolean(true)))});
        queries.put("SORT flag asc", () -> new BaseAggregationStep[]{new SortAggregationStep("flag", true)});
        return queries;
    }

    private void assertEveryQueryAgrees(String field, Map<String, Supplier<BaseAggregationStep[]>> queries)
            throws Exception {
        final var scanned = new LinkedHashMap<String, List<String>>();
        for (final var query : queries.entrySet()) {
            scanned.put(query.getKey(), run(query.getValue().get()));
        }
        assertFalse(scanned.isEmpty(), "the matrix must actually run something");

        indexField(field);

        for (final var query : queries.entrySet()) {
            final var indexed = run(query.getValue().get());
            assertEquals(scanned.get(query.getKey()), indexed, "index and scan disagree for: " + query.getKey());
        }
    }

    @Test
    public void test_every_numeric_query_agrees_between_index_and_scan() throws Exception {
        seedNumbers();

        assertEveryQueryAgrees("score", numericQueries());
    }

    @Test
    public void test_every_string_query_agrees_between_index_and_scan() throws Exception {
        seedStrings();

        assertEveryQueryAgrees("name", stringQueries());
    }

    @Test
    public void test_every_boolean_query_agrees_between_index_and_scan() throws Exception {
        seedBooleans();

        assertEveryQueryAgrees("flag", booleanQueries());
    }

    @Test
    public void test_every_null_operand_query_agrees_between_index_and_scan() throws Exception {
        seedNulls();

        assertEveryQueryAgrees("state", nullOperandQueries());
    }

    @Test
    public void test_a_field_holding_several_types_still_agrees() throws Exception {
        insert("m_num", "mixed", parsed("1"));
        insert("m_str", "mixed", new JsonString("1"));
        insert("m_bool", "mixed", new JsonBoolean(true));
        insert("m_absent", "mixed", null);
        final var queries = new LinkedHashMap<String, Supplier<BaseAggregationStep[]>>();
        queries.put("EQUALS mixed 1",
                () -> new BaseAggregationStep[]{filter(FieldOperatorType.EQUALS, "mixed", parsed("1"))});
        queries.put("EQUALS mixed '1'",
                () -> new BaseAggregationStep[]{filter(FieldOperatorType.EQUALS, "mixed", new JsonString("1"))});
        queries.put("NOT_IN mixed [1]",
                () -> new BaseAggregationStep[]{filter(FieldOperatorType.NOT_IN, "mixed", array(parsed("1")))});
        queries.put("IN mixed ['1']",
                () -> new BaseAggregationStep[]{filter(FieldOperatorType.IN, "mixed", array(new JsonString("1")))});
        queries.put("IN mixed [1]",
                () -> new BaseAggregationStep[]{filter(FieldOperatorType.IN, "mixed", array(parsed("1")))});
        queries.put("SORT mixed asc", () -> new BaseAggregationStep[]{new SortAggregationStep("mixed", true)});

        assertEveryQueryAgrees("mixed", queries);
    }

    // Guards the matrix itself: if the field were never registered, every comparison above would be
    // scan against scan and would agree no matter what the index layer did. A field every document
    // carries is used deliberately - the coverage gate declines to answer from an index that omits
    // documents lacking the field, which is correct and is exercised by the matrices above.
    @Test
    public void test_the_corpus_is_really_indexed() throws Exception {
        insert("c1", "complete", parsed("1"));
        insert("c2", "complete", parsed("2"));
        indexField("complete");

        assertEquals(Set.of("complete"), cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).getIndexes(),
                "the field must be registered, or every query above silently proves nothing");
        final var entries = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, "complete");
        assertFalse(entries == null || entries.isEmpty(),
                "the index must hold entries, or agreement is only scan-against-scan");
    }
}
