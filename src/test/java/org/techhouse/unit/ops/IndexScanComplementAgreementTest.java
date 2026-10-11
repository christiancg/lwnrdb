package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexScanComplementAgreementTest extends FilterResolutionSupport {
    private static final String FIELD = "x";

    private void assertIndexAgreesWithScan(BaseAggregationStep... steps) throws Exception {
        final var scanned = rowsJson(steps);
        indexField(FIELD);
        assertEquals(scanned, rowsJson(steps));
    }

    private static FilterAggregationStep where(FieldOperatorType type, JsonBaseElement operand) {
        return filter(type, FIELD, operand);
    }

    private static JsonObject objectWithA(int a) {
        final var object = new JsonObject();
        object.add("a", new JsonNumber(a));
        return object;
    }

    private Set<String> idsFromIndex(FieldOperatorType type, JsonBaseElement operand) throws IOException {
        final Object value = switch (operand) {
            case JsonNumber number -> number.getValue();
            case JsonBoolean bool -> bool.getValue();
            case JsonString string -> string.getValue();
            default -> operand;
        };
        return cache.getIdsFromIndex(TestGlobals.DB, TestGlobals.COLL, FIELD, new FieldOperator(type, FIELD, operand),
                value);
    }

    @Test
    public void test_not_equals_object_operand_agrees_across_container_and_scalar_values() throws Exception {
        insert("1", FIELD, objectWithA(1));
        insert("2", FIELD, arrayOf(new JsonNumber(1), new JsonNumber(2)));
        insert("3", FIELD, new JsonString("hello"));
        final var scanned = rowsJson(where(FieldOperatorType.NOT_EQUALS, objectWithA(1)));
        assertEquals(2, scanned.size());
        assertIndexAgreesWithScan(where(FieldOperatorType.NOT_EQUALS, objectWithA(1)));
    }

    @Test
    public void test_not_equals_array_operand_agrees_across_container_and_scalar_values() throws Exception {
        insert("1", FIELD, arrayOf(new JsonNumber(1)));
        insert("2", FIELD, objectWithA(2));
        insert("3", FIELD, new JsonNumber(5));
        assertIndexAgreesWithScan(where(FieldOperatorType.NOT_EQUALS, arrayOf(new JsonNumber(1))));
    }

    @Test
    public void test_not_equals_object_operand_matches_an_explicit_null() throws Exception {
        insert("1", FIELD, objectWithA(1));
        insert("2", FIELD, objectWithA(2));
        insert("3", FIELD, JsonNull.INSTANCE);
        assertEquals(2, rowsJson(where(FieldOperatorType.NOT_EQUALS, objectWithA(1))).size());
        assertIndexAgreesWithScan(where(FieldOperatorType.NOT_EQUALS, objectWithA(1)));
    }

    @Test
    public void test_not_in_matches_an_explicit_null_with_a_single_type_index() throws Exception {
        insert("1", FIELD, new JsonString("a"));
        insert("2", FIELD, new JsonString("b"));
        insert("3", FIELD, JsonNull.INSTANCE);
        assertEquals(2, rowsJson(where(FieldOperatorType.NOT_IN, arrayOf(new JsonString("a")))).size());
        assertIndexAgreesWithScan(where(FieldOperatorType.NOT_IN, arrayOf(new JsonString("a"))));
    }

    @Test
    public void test_not_in_count_matches_filter_with_an_explicit_null() throws Exception {
        insert("1", FIELD, new JsonString("a"));
        insert("2", FIELD, new JsonString("b"));
        insert("3", FIELD, JsonNull.INSTANCE);
        assertIndexAgreesWithScan(where(FieldOperatorType.NOT_IN, arrayOf(new JsonString("a"))),
                new CountAggregationStep());
    }

    @Test
    public void test_not_in_object_operand_matches_an_explicit_null() throws Exception {
        insert("1", FIELD, objectWithA(1));
        insert("2", FIELD, objectWithA(2));
        insert("3", FIELD, JsonNull.INSTANCE);
        assertIndexAgreesWithScan(where(FieldOperatorType.NOT_IN, arrayOf(objectWithA(1))));
    }

    @Test
    public void test_complements_still_use_the_index_when_every_document_shares_one_kind() throws Exception {
        insert("1", FIELD, objectWithA(1));
        insert("2", FIELD, objectWithA(2));
        indexField(FIELD);
        assertEquals(Set.of("2"), idsFromIndex(FieldOperatorType.NOT_EQUALS, objectWithA(1)));
        assertEquals(Set.of("2"), idsFromIndex(FieldOperatorType.NOT_IN, arrayOf(objectWithA(1))));
    }

    @Test
    public void test_scalar_not_in_still_uses_the_index_when_every_document_is_covered() throws Exception {
        insert("1", FIELD, new JsonString("a"));
        insert("2", FIELD, new JsonString("b"));
        indexField(FIELD);
        assertNotNull(idsFromIndex(FieldOperatorType.NOT_IN, arrayOf(new JsonString("a"))));
    }

    @Test
    public void test_complements_decline_the_index_when_a_document_is_not_covered() throws Exception {
        insert("1", FIELD, objectWithA(1));
        insert("2", FIELD, JsonNull.INSTANCE);
        indexField(FIELD);
        assertNull(idsFromIndex(FieldOperatorType.NOT_EQUALS, objectWithA(1)));
        assertNull(idsFromIndex(FieldOperatorType.NOT_IN, arrayOf(objectWithA(1))));
        assertNotNull(idsFromIndex(FieldOperatorType.EQUALS, objectWithA(1)));
    }

    @Test
    public void test_array_not_equals_declines_the_index_when_another_kind_is_indexed() throws Exception {
        insert("1", FIELD, arrayOf(new JsonNumber(1)));
        insert("2", FIELD, new JsonString("s"));
        indexField(FIELD);
        assertNull(idsFromIndex(FieldOperatorType.NOT_EQUALS, arrayOf(new JsonNumber(1))));
        assertNotNull(idsFromIndex(FieldOperatorType.EQUALS, arrayOf(new JsonNumber(1))));
    }
    private void storeMixedKinds() throws IOException {
        insert("num", FIELD, new JsonNumber(5));
        insert("str", FIELD, new JsonString("a"));
        insert("bool", FIELD, new JsonBoolean(true));
        insert("nul", FIELD, JsonNull.INSTANCE);
        insert("arr", FIELD, arrayOf(new JsonNumber(5)));
        insert("obj", FIELD, objectWithA(5));
    }

    @Test
    public void test_scalar_not_equals_matches_every_other_kind_on_a_mixed_field() throws Exception {
        storeMixedKinds();
        assertEquals(6, rowsJson(where(FieldOperatorType.NOT_EQUALS, new JsonString("b"))).size());
        assertEquals(5, rowsJson(where(FieldOperatorType.NOT_EQUALS, new JsonNumber(5))).size());
        assertEquals(5, rowsJson(where(FieldOperatorType.NOT_EQUALS, new JsonBoolean(true))).size());
    }

    @Test
    public void test_scalar_not_equals_agrees_with_the_scan_on_a_mixed_field() throws Exception {
        storeMixedKinds();
        assertIndexAgreesWithScan(where(FieldOperatorType.NOT_EQUALS, new JsonString("b")));
    }

    @Test
    public void test_scalar_not_equals_count_agrees_with_the_scan_on_a_mixed_field() throws Exception {
        storeMixedKinds();
        assertIndexAgreesWithScan(where(FieldOperatorType.NOT_EQUALS, new JsonNumber(5)), new CountAggregationStep());
    }

    @Test
    public void test_scalar_not_equals_agrees_with_not_in_on_a_mixed_field() throws Exception {
        storeMixedKinds();
        indexField(FIELD);
        assertEquals(rowsJson(where(FieldOperatorType.NOT_IN, arrayOf(new JsonString("a")))),
                rowsJson(where(FieldOperatorType.NOT_EQUALS, new JsonString("a"))));
    }

    @Test
    public void test_scalar_not_equals_declines_the_index_when_another_type_is_indexed() throws Exception {
        insert("1", FIELD, new JsonNumber(1));
        insert("2", FIELD, new JsonString("s"));
        indexField(FIELD);
        assertNull(idsFromIndex(FieldOperatorType.NOT_EQUALS, new JsonNumber(1)));
        assertNull(idsFromIndex(FieldOperatorType.NOT_EQUALS, new JsonString("t")));
        assertNotNull(idsFromIndex(FieldOperatorType.EQUALS, new JsonNumber(1)));
    }

    @Test
    public void test_scalar_not_equals_declines_the_index_when_a_document_is_not_covered() throws Exception {
        insert("1", FIELD, new JsonNumber(1));
        insert("2", FIELD, JsonNull.INSTANCE);
        indexField(FIELD);
        assertNull(idsFromIndex(FieldOperatorType.NOT_EQUALS, new JsonNumber(1)));
    }

    @Test
    public void test_scalar_not_equals_still_uses_the_index_on_a_covered_homogeneous_field() throws Exception {
        insert("1", FIELD, new JsonNumber(1));
        insert("2", FIELD, new JsonNumber(2));
        indexField(FIELD);
        assertEquals(Set.of("2"), idsFromIndex(FieldOperatorType.NOT_EQUALS, new JsonNumber(1)));
    }

    @Test
    public void test_a_pending_write_of_another_kind_is_re_derived_for_scalar_not_equals() throws Exception {
        insert("1", FIELD, new JsonNumber(1));
        insert("2", FIELD, new JsonNumber(2));
        indexField(FIELD);
        insert("2", FIELD, new JsonString("now a string"));
        IocContainer.get(PendingIndexWrites.class).mark(TestGlobals.DB, TestGlobals.COLL, "2");
        assertEquals(1, rowsJson(where(FieldOperatorType.NOT_EQUALS, new JsonNumber(1))).size());
        assertEquals(2, rowsJson(where(FieldOperatorType.NOT_EQUALS, new JsonNumber(2))).size());
    }

    private void deleteWithIndexMaintenancePending(String id) throws IOException {
        TestUtils.uncacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, id);
        IocContainer.get(PendingIndexWrites.class).mark(TestGlobals.DB, TestGlobals.COLL, id);
    }

    private void storeOneAndNullThenIndex() throws Exception {
        insert("a", FIELD, new JsonNumber(1));
        insert("n", FIELD, JsonNull.INSTANCE);
        indexField(FIELD);
    }

    @Test
    public void test_not_equals_keeps_a_committed_null_while_another_delete_is_pending() throws Exception {
        storeOneAndNullThenIndex();
        deleteWithIndexMaintenancePending("a");

        final var rows = rowsJson(where(FieldOperatorType.NOT_EQUALS, new JsonNumber(5)));

        assertEquals(1, rows.size(), "the stale id of the deleted document must not stand in for the null one");
        assertEquals("{\"_id\":\"n\",\"x\":null}", rows.getFirst().replace(" ", ""));
    }

    @Test
    public void test_not_in_keeps_a_committed_null_while_another_delete_is_pending() throws Exception {
        storeOneAndNullThenIndex();
        deleteWithIndexMaintenancePending("a");

        assertEquals(1, rowsJson(where(FieldOperatorType.NOT_IN, arrayOf(new JsonNumber(5)))).size());
    }

    @Test
    public void test_index_only_count_agrees_with_the_scan_while_a_delete_is_pending() throws Exception {
        storeOneAndNullThenIndex();
        deleteWithIndexMaintenancePending("a");

        assertNull(idsFromIndex(FieldOperatorType.NOT_EQUALS, new JsonNumber(5)),
                "an unindexed committed document must make the complement decline the index");
        assertEquals(List.of("{\"count\":1}"),
                rowsJson(where(FieldOperatorType.NOT_EQUALS, new JsonNumber(5)), new CountAggregationStep()).stream()
                        .map(row -> row.replace(" ", "")).toList());
    }

    @Test
    public void test_the_complement_still_uses_the_index_when_every_non_pending_id_is_indexed() throws Exception {
        insert("a", FIELD, new JsonNumber(1));
        insert("b", FIELD, new JsonNumber(2));
        insert("c", FIELD, new JsonNumber(3));
        indexField(FIELD);
        deleteWithIndexMaintenancePending("c");

        assertNotNull(idsFromIndex(FieldOperatorType.NOT_EQUALS, new JsonNumber(5)),
                "a pending id is re-derived by the reconciler, so it must not force a scan");
    }
}
