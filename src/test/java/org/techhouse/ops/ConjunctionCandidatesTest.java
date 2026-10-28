package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.unit.ops.FilterResolutionSupport;

public class ConjunctionCandidatesTest extends FilterResolutionSupport {
    private static final String PRICE = "price";
    private static final String LABEL = "label";
    private static final int DOCUMENTS = 10;
    private final PendingIndexWrites pending = IocContainer.get(PendingIndexWrites.class);

    @AfterEach
    public void clearPending() {
        pending.clearCollection(TestGlobals.DB, TestGlobals.COLL);
    }

    private void seed() throws Exception {
        for (var i = 0; i < DOCUMENTS; i++) {
            put("p" + i, i * 10.0, i % 2 == 0 ? "even" : "odd");
        }
        indexField(PRICE);
    }

    private void put(String id, Object price, String label) throws IOException {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add(PRICE, price == null ? JsonNull.INSTANCE : new JsonNumber((Number) price));
        object.add(LABEL, new JsonString(label));
        final var entry = org.techhouse.data.DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        entry.set_id(id);
        org.techhouse.test.TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private static FieldOperator leaf(FieldOperatorType type, String field, JsonBaseElement value) {
        return new FieldOperator(type, field, value);
    }

    private static FieldOperator price(FieldOperatorType type, double value) {
        return leaf(type, PRICE, new JsonNumber(value));
    }

    private static ConjunctionOperator conjunction(ConjunctionOperatorType type, BaseOperator... legs) {
        return new ConjunctionOperator(type, List.of(legs));
    }

    private Set<String> filtered(BaseOperator operator, AnalyzeContext analyze) throws IOException {
        return FilterOperatorHelper
                .processOperator(operator, null, TestGlobals.DB, TestGlobals.COLL,
                        PipelineContext.of(0L, Long.MAX_VALUE, analyze))
                .map(row -> row.get(Globals.PK_FIELD).asJsonString().getValue())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private Set<String> candidates(ConjunctionOperator operator) throws IOException {
        return ConjunctionCandidates.of(operator, TestGlobals.DB, TestGlobals.COLL, PipelineContext.withoutScripts());
    }

    @Test
    public void test_an_and_of_same_field_bounds_reads_only_the_matching_documents() throws Exception {
        seed();
        final var analyze = new AnalyzeContext();

        final var result = filtered(conjunction(ConjunctionOperatorType.AND,
                price(FieldOperatorType.GREATER_THAN_EQUALS, 20), price(FieldOperatorType.SMALLER_THAN, 50)), analyze);

        assertEquals(Set.of("p2", "p3", "p4"), result);
        assertEquals(3, analyze.getDocumentsScanned(), "only the documents inside the range are read");
    }

    @Test
    public void test_an_and_with_an_unindexed_leg_narrows_by_the_indexed_one() throws Exception {
        seed();
        final var analyze = new AnalyzeContext();

        final var result = filtered(conjunction(ConjunctionOperatorType.AND,
                price(FieldOperatorType.GREATER_THAN_EQUALS, 20), price(FieldOperatorType.SMALLER_THAN, 50),
                leaf(FieldOperatorType.EQUALS, LABEL, new JsonString("even"))), analyze);

        assertEquals(Set.of("p2", "p4"), result);
        assertEquals(3, analyze.getDocumentsScanned(), "the unindexed leg is tested on the survivors");
    }

    @Test
    public void test_or_and_xor_narrow_only_when_every_leg_is_indexed() throws Exception {
        seed();
        final var indexedLegs = conjunction(ConjunctionOperatorType.OR, price(FieldOperatorType.SMALLER_THAN, 20),
                price(FieldOperatorType.GREATER_THAN_EQUALS, 80));
        final var mixedLegs = conjunction(ConjunctionOperatorType.XOR, price(FieldOperatorType.SMALLER_THAN, 20),
                leaf(FieldOperatorType.EQUALS, LABEL, new JsonString("even")));

        assertEquals(Set.of("p0", "p1", "p8", "p9"), candidates(indexedLegs));
        assertNull(candidates(mixedLegs));
        assertEquals(Set.of("p0", "p1", "p8", "p9"), filtered(indexedLegs, new AnalyzeContext()));
        assertEquals(Set.of("p1", "p2", "p4", "p6", "p8"), filtered(mixedLegs, new AnalyzeContext()));
    }

    @Test
    public void test_nor_and_nand_keep_the_scan() throws Exception {
        seed();
        for (final var type : List.of(ConjunctionOperatorType.NOR, ConjunctionOperatorType.NAND)) {
            assertNull(candidates(conjunction(type, price(FieldOperatorType.SMALLER_THAN, 20),
                    price(FieldOperatorType.GREATER_THAN_EQUALS, 80))));
        }
    }

    @Test
    public void test_a_nearest_leg_disables_narrowing() throws Exception {
        seed();
        final var nearest = new CustomOperator("nearest", "embedding", new JsonArray(), new JsonObject());

        assertNull(candidates(conjunction(ConjunctionOperatorType.AND, price(FieldOperatorType.GREATER_THAN, 0),
                conjunction(ConjunctionOperatorType.OR, nearest, price(FieldOperatorType.SMALLER_THAN, 5)))));
    }

    @Test
    public void test_a_pending_document_is_judged_by_its_current_value() throws Exception {
        seed();
        put("p3", 999.0, "odd");
        put("late", 35.0, "odd");
        pending.markRecovered(TestGlobals.DB, TestGlobals.COLL, List.of("p3", "late"));

        final var result = filtered(conjunction(ConjunctionOperatorType.AND,
                price(FieldOperatorType.GREATER_THAN_EQUALS, 20), price(FieldOperatorType.SMALLER_THAN, 50)),
                new AnalyzeContext());

        assertEquals(Set.of("late", "p2", "p4"), result, "the stale index entry of p3 must not answer for it");
    }

    @Test
    public void test_a_null_valued_document_is_neither_indexed_nor_matched() throws Exception {
        put("hole", null, "odd");
        seed();
        final var range = conjunction(ConjunctionOperatorType.AND, price(FieldOperatorType.GREATER_THAN_EQUALS, 20),
                price(FieldOperatorType.SMALLER_THAN, 50));

        assertEquals(Set.of("p2", "p3", "p4"), candidates(range));
        assertEquals(scanned(range), new ArrayList<>(filtered(range, new AnalyzeContext())));
    }

    @Test
    public void test_narrowed_and_scanned_answers_are_the_same_multiset() throws Exception {
        seed();
        final var types = List.of(FieldOperatorType.GREATER_THAN, FieldOperatorType.GREATER_THAN_EQUALS,
                FieldOperatorType.SMALLER_THAN, FieldOperatorType.SMALLER_THAN_EQUALS);
        for (final var lowerType : types.subList(0, 2)) {
            for (final var upperType : types.subList(2, 4)) {
                assertEveryBoundAgrees(lowerType, upperType);
            }
        }
    }

    private void assertEveryBoundAgrees(FieldOperatorType lowerType, FieldOperatorType upperType) throws IOException {
        for (final var low : List.of(-5.0, 0.0, 20.0, 45.0, 90.0)) {
            for (final var high : List.of(0.0, 30.0, 50.0, 95.0)) {
                final var range = conjunction(ConjunctionOperatorType.AND, price(lowerType, low),
                        price(upperType, high));
                assertEquals(scanned(range), new ArrayList<>(filtered(range, new AnalyzeContext())),
                        lowerType + " " + low + " / " + upperType + " " + high);
            }
        }
    }

    @Test
    public void test_a_hash_indexed_leg_narrows_and_is_retested() throws Exception {
        seed();
        for (var i = 0; i < DOCUMENTS; i++) {
            final var meta = new JsonObject();
            meta.addProperty("k", i % 3);
            final var object = cache.getById(TestGlobals.DB, TestGlobals.COLL,
                    cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).get(i)).getData();
            object.add("meta", meta);
        }
        indexField("meta");
        final var wanted = new JsonObject();
        wanted.addProperty("k", 0);
        final var analyze = new AnalyzeContext();

        final var result = filtered(conjunction(ConjunctionOperatorType.AND,
                leaf(FieldOperatorType.EQUALS, "meta", wanted), price(FieldOperatorType.GREATER_THAN_EQUALS, 20)),
                analyze);

        assertEquals(Set.of("p3", "p6", "p9"), result);
        assertEquals(3, analyze.getDocumentsScanned(), "the hash candidates narrow before any document is read");
    }

    private List<String> scanned(ConjunctionOperator range) throws IOException {
        final var everyDocument = cache.getWholeCollection(TestGlobals.DB, TestGlobals.COLL).values().stream()
                .map(org.techhouse.data.DbEntry::getData);
        return FilterOperatorHelper
                .processOperator(range, everyDocument, TestGlobals.DB, TestGlobals.COLL,
                        PipelineContext.withoutScripts())
                .map(row -> row.get(Globals.PK_FIELD).asJsonString().getValue()).sorted().toList();
    }
}
