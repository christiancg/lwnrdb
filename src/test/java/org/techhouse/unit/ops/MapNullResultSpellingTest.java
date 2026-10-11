package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.MapOperator;

public class MapNullResultSpellingTest extends FilterResolutionSupport {
    private static final String SOURCE_FIELD = "price";
    private static final String DERIVED_FIELD = "avgPrice";

    @BeforeEach
    public void seedPrices() throws IOException {
        insert("withPrice", SOURCE_FIELD, new JsonNumber(10));
        insert("withoutPrice", SOURCE_FIELD, null);
    }

    private static MapAggregationStep averagePriceStep() {
        final var operands = new JsonArray();
        operands.add(new JsonString(SOURCE_FIELD));
        final MapOperator operator = new AddFieldMapOperator(DERIVED_FIELD, null,
                new ArrayParamMidOperator(MidOperationType.AVG, operands));
        return new MapAggregationStep(List.of(operator));
    }

    private static MapAggregationStep derivedPlusOneStep() {
        final var operands = new JsonArray();
        operands.add(new JsonString(DERIVED_FIELD));
        operands.add(new JsonNumber(1));
        final MapOperator operator = new AddFieldMapOperator("plusOne", null,
                new ArrayParamMidOperator(MidOperationType.SUM, operands));
        return new MapAggregationStep(List.of(operator));
    }

    private static List<String> idsOf(List<JsonObject> rows) {
        return rows.stream().map(row -> row.get(Globals.PK_FIELD).asJsonString().getValue()).toList();
    }

    private static JsonObject rowFor(List<JsonObject> rows, String id) {
        return rows.stream().filter(row -> id.equals(row.get(Globals.PK_FIELD).asJsonString().getValue())).findFirst()
                .orElseThrow();
    }

    @Test
    public void test_a_fold_with_no_valid_operand_is_a_json_null() throws Exception {
        final var rows = rows(averagePriceStep());

        assertSame(JsonNull.INSTANCE, rowFor(rows, "withoutPrice").get(DERIVED_FIELD));
    }

    @Test
    public void test_sort_after_a_map_with_a_null_result_does_not_throw() throws Exception {
        final var rows = rows(averagePriceStep(), new SortAggregationStep(DERIVED_FIELD, true));

        assertEquals(List.of("withPrice", "withoutPrice"), idsOf(rows));
    }

    @Test
    public void test_a_numeric_filter_after_a_map_with_a_null_result_does_not_throw() throws Exception {
        final var rows = rows(averagePriceStep(), new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.GREATER_THAN, DERIVED_FIELD, new JsonNumber(0))));

        assertEquals(List.of("withPrice"), idsOf(rows));
    }

    @Test
    public void test_a_second_map_reading_a_null_result_does_not_throw() throws Exception {
        final var rows = rows(averagePriceStep(), derivedPlusOneStep());

        assertEquals(11.0, rowFor(rows, "withPrice").get("plusOne").asJsonNumber().getValue().doubleValue());
        assertEquals(1.0, rowFor(rows, "withoutPrice").get("plusOne").asJsonNumber().getValue().doubleValue(),
                "a null operand is skipped, so the literal seeds the fold instead of throwing");
    }

    @Test
    public void test_filter_equals_null_matches_the_row_the_response_shows_as_null() throws Exception {
        final var rows = rows(averagePriceStep(), new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, DERIVED_FIELD, JsonNull.INSTANCE)));

        assertEquals(List.of("withoutPrice"), idsOf(rows));
    }

    @Test
    public void test_filter_not_equals_null_excludes_it() throws Exception {
        final var rows = rows(averagePriceStep(), new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.NOT_EQUALS, DERIVED_FIELD, JsonNull.INSTANCE)));

        assertEquals(List.of("withPrice"), idsOf(rows));
    }

    @Test
    public void test_group_by_a_null_map_result_keys_the_group_on_json_null() throws Exception {
        final var rows = rows(averagePriceStep(), new GroupByAggregationStep(DERIVED_FIELD));

        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(row -> row.get(DERIVED_FIELD) == JsonNull.INSTANCE));
    }
}
