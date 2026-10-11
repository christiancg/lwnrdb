package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.utils.JsonUtils;

public class AggregationSortCustomOrderTest extends FilterResolutionSupport {
    private static final String FIELD = "marker";
    private static final String LOWEST_GEO = "#geo(8.0,5.0)";
    private static final String MIDDLE_GEO = "#geo(9.0,5.0)";
    private static final String HIGHEST_GEO = "#geo(10.0,5.0)";
    private static final String TIE_FIELD = "meta";

    private void insertValue(String id, JsonString value) throws IOException {
        insert(id, FIELD, value);
    }

    private void insertGeo(String id, String wireValue) throws IOException {
        insertValue(id, new JsonGeo(wireValue));
    }

    private void insertDateTime(String id, String isoValue) throws IOException {
        insertValue(id, new JsonDateTime("#datetime(" + isoValue + ")"));
    }

    private void seedGeoOutOfTextOrder() throws IOException {
        insertGeo("x", HIGHEST_GEO);
        insertGeo("y", MIDDLE_GEO);
        insertGeo("z", LOWEST_GEO);
    }

    @Test
    public void test_geo_sort_uses_the_same_order_as_the_range_filter() throws Exception {
        seedGeoOutOfTextOrder();

        final var sorted = idsInOrder(new SortAggregationStep(FIELD, true));
        final var above = idsInOrder(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.GREATER_THAN, FIELD, new JsonGeo(MIDDLE_GEO))));

        assertEquals(List.of("z", "y", "x"), sorted,
                "SORT ordered by wire text, which puts #geo(10.0,5.0) first and contradicts GREATER_THAN");
        assertEquals(List.of("x"), above, "only the highest point is above the middle one");
    }

    @Test
    public void test_geo_sort_is_the_same_indexed_and_scanned() throws Exception {
        seedGeoOutOfTextOrder();
        final var viaScan = idsInOrder(new SortAggregationStep(FIELD, true));
        indexField(FIELD);

        final var viaIndex = idsInOrder(new SortAggregationStep(FIELD, true));

        assertEquals(viaScan, viaIndex);
        assertEquals(List.of("x", "y", "z"), idsInOrder(new SortAggregationStep(FIELD, false)));
    }

    private void seedEqualInstantsOutOfIdOrder() throws IOException {
        insertDateTime("b", "2024-01-01T10:00");
        insertDateTime("a", "2024-01-01T10:00:00");
        insertDateTime("c", "2024-06-01T10:00:00");
    }

    @Test
    public void test_equal_datetimes_break_on_id_on_both_paths() throws Exception {
        seedEqualInstantsOutOfIdOrder();
        final var viaScan = idsInOrder(new SortAggregationStep(FIELD, true));
        indexField(FIELD);

        final var viaIndex = idsInOrder(new SortAggregationStep(FIELD, true));

        assertEquals(List.of("a", "b", "c"), viaScan,
                "the two spellings are the same instant, so the tie breaks on _id");
        assertEquals(viaScan, viaIndex);
    }

    @Test
    public void test_equal_datetimes_tie_on_the_sorted_ids_index_path() throws Exception {
        seedEqualInstantsOutOfIdOrder();
        indexField(FIELD);

        final var orderedIds = IndexHelper.getSortedIdsForField(TestGlobals.DB, TestGlobals.COLL, FIELD,
                (left, right) -> JsonUtils.compareSortKeysAscending(IndexHelper.indexValueToElement(left.getValue()),
                        IndexHelper.indexValueToElement(right.getValue())),
                Long.MAX_VALUE);

        assertNotNull(orderedIds, "a collection of scalar custom values has no hash index, so this path applies");
        assertEquals(List.of("a", "b", "c"), orderedIds,
                "two entries holding the same instant are one run and their ids merge sorted");
    }

    @Test
    public void test_a_limited_datetime_sort_returns_the_same_page_either_way() throws Exception {
        seedEqualInstantsOutOfIdOrder();
        final var viaScan = idsInOrder(new SortAggregationStep(FIELD, true), new LimitAggregationStep(1));
        indexField(FIELD);

        final var viaIndex = idsInOrder(new SortAggregationStep(FIELD, true), new LimitAggregationStep(1));

        assertEquals(List.of("a"), viaScan, "with LIMIT the tie-break decides the result set, not merely its order");
        assertEquals(viaScan, viaIndex);
    }

    @Test
    public void test_custom_values_sort_after_plain_strings_on_both_paths() throws Exception {
        insertValue("s", new JsonString("zzz"));
        insertGeo("g", LOWEST_GEO);
        final var viaScan = idsInOrder(new SortAggregationStep(FIELD, true));
        indexField(FIELD);

        final var viaIndex = idsInOrder(new SortAggregationStep(FIELD, true));

        assertEquals(List.of("s", "g"), viaScan, "a custom value ranks above every plain string");
        assertEquals(viaScan, viaIndex);
    }

    private void insertObjectValued(String id, int inner) throws IOException {
        final var nested = new JsonObject();
        nested.addProperty("x", inner);
        insert(id, TIE_FIELD, nested);
    }

    private void seedOutOfIdOrder() throws IOException {
        insertObjectValued("z", 1);
        insertObjectValued("a", 2);
        insertObjectValued("m", 3);
    }

    @Test
    public void test_object_sort_keys_order_by_id_on_the_index_path_as_on_the_scan() throws Exception {
        seedOutOfIdOrder();
        final var viaScan = idsInOrder(new SortAggregationStep(TIE_FIELD, true));
        indexField(TIE_FIELD);

        final var viaIndex = idsInOrder(new SortAggregationStep(TIE_FIELD, true));

        assertEquals(List.of("a", "m", "z"), viaScan, "the scan breaks ties on _id");
        assertEquals(viaScan, viaIndex,
                "every object value ties, so the index path concatenated whole entries in hash-bucket order"
                        + " and disagreed with the scan");
    }

    @Test
    public void test_a_limited_object_sort_returns_the_same_page_either_way() throws Exception {
        seedOutOfIdOrder();
        final var viaScan = idsInOrder(new SortAggregationStep(TIE_FIELD, true), new LimitAggregationStep(2));
        indexField(TIE_FIELD);

        final var viaIndex = idsInOrder(new SortAggregationStep(TIE_FIELD, true), new LimitAggregationStep(2));

        assertEquals(List.of("a", "m"), viaScan);
        assertEquals(viaScan, viaIndex, "with LIMIT the tie-break decides the result set, not merely its order");
    }

    @Test
    public void test_descending_object_sort_also_agrees() throws Exception {
        seedOutOfIdOrder();
        final var viaScan = idsInOrder(new SortAggregationStep(TIE_FIELD, false));
        indexField(TIE_FIELD);

        final var viaIndex = idsInOrder(new SortAggregationStep(TIE_FIELD, false));

        assertEquals(viaScan, viaIndex, "the tie-break must not depend on the sort direction");
    }

    private void insertNumberValued(String id, double value) throws IOException {
        insert(id, TIE_FIELD, new JsonNumber(value));
    }

    @Test
    public void test_negative_zero_sorts_the_same_way_on_both_paths() throws Exception {
        insertNumberValued("z", -0.0);
        insertNumberValued("a", 0.0);
        final var viaScan = idsInOrder(new SortAggregationStep(TIE_FIELD, true));
        indexField(TIE_FIELD);

        final var viaIndex = idsInOrder(new SortAggregationStep(TIE_FIELD, true));

        assertEquals(List.of("a", "z"), viaScan,
                "the index keys both spellings as 0, so the scan must tie them and break on _id too");
        assertEquals(viaScan, viaIndex);
    }
}
