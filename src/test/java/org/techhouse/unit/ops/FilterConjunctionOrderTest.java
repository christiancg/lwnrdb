package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.test.TestUtils;

public class FilterConjunctionOrderTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private static JsonObject doc(String id, String role, String active) {
        final var obj = new JsonObject();
        if (id != null) {
            obj.add(Globals.PK_FIELD, new JsonString(id));
        }
        obj.addProperty("role", role);
        obj.addProperty("active", active);
        return obj;
    }

    private static FieldOperator eq(String field, String value) {
        return new FieldOperator(FieldOperatorType.EQUALS, field, new JsonString(value));
    }

    private static BaseAggregationStep filter(BaseOperator operator) {
        return new FilterAggregationStep(operator);
    }

    private static ConjunctionOperator conjunction(ConjunctionOperatorType type) {
        return new ConjunctionOperator(type, List.of(eq("role", "admin"), eq("active", "yes")));
    }

    private static List<String> ids(List<JsonObject> rows) {
        return rows.stream().map(row -> row.get(Globals.PK_FIELD).asJsonString().getValue()).toList();
    }

    private static List<String> run(List<JsonObject> source, BaseAggregationStep step) throws IOException {
        return ids(AggregationOperationHelper.processStepsOnStream(List.of(step), source.stream()));
    }

    private static List<JsonObject> sortedSource() {
        return List.of(doc("z", "user", "yes"), doc("y", "admin", "no"), doc("x", "admin", "yes"),
                doc("w", "user", "no"), doc("v", "admin", "yes"), doc("u", "user", "yes"));
    }

    @Test
    public void test_and_after_a_sorted_input_keeps_its_order() throws IOException {
        assertEquals(List.of("x", "v"), run(sortedSource(), filter(conjunction(ConjunctionOperatorType.AND))));
    }

    @Test
    public void test_or_after_a_sorted_input_keeps_its_order() throws IOException {
        assertEquals(List.of("z", "y", "x", "v", "u"),
                run(sortedSource(), filter(conjunction(ConjunctionOperatorType.OR))),
                "OR must keep input order rather than listing each branch's matches in turn");
    }

    @Test
    public void test_xor_after_a_sorted_input_keeps_its_order() throws IOException {
        assertEquals(List.of("z", "y", "u"), run(sortedSource(), filter(conjunction(ConjunctionOperatorType.XOR))));
    }

    @Test
    public void test_nor_after_a_sorted_input_keeps_its_order() throws IOException {
        assertEquals(List.of("w"), run(sortedSource(), filter(conjunction(ConjunctionOperatorType.NOR))));
    }

    @Test
    public void test_nand_after_a_sorted_input_keeps_its_order() throws IOException {
        assertEquals(List.of("z", "y", "w", "u"),
                run(sortedSource(), filter(conjunction(ConjunctionOperatorType.NAND))));
    }

    @Test
    public void test_a_nested_conjunction_keeps_its_order() throws IOException {
        final var nested = new ConjunctionOperator(ConjunctionOperatorType.OR,
                List.of(conjunction(ConjunctionOperatorType.AND), eq("role", "user")));

        assertEquals(List.of("z", "x", "w", "v", "u"), run(sortedSource(), filter(nested)));
    }

    @Test
    public void test_a_conjunction_over_an_empty_input_is_empty() throws IOException {
        assertTrue(run(List.of(), filter(conjunction(ConjunctionOperatorType.NOR))).isEmpty());
    }

    @Test
    public void test_a_single_operand_conjunction_behaves_like_its_operand() throws IOException {
        final var single = new ConjunctionOperator(ConjunctionOperatorType.AND, List.of(eq("role", "admin")));

        assertEquals(List.of("y", "x", "v"), run(sortedSource(), filter(single)));
    }

    @Test
    public void test_or_counts_equal_rows_without_an_id_like_a_plain_filter() throws IOException {
        final var source = List.of(doc(null, "admin", "yes"), doc(null, "admin", "yes"));
        final var plain = AggregationOperationHelper.processStepsOnStream(List.of(filter(eq("role", "admin"))),
                source.stream());
        final var single = new ConjunctionOperator(ConjunctionOperatorType.OR, List.of(eq("role", "admin")));

        final var conjoined = AggregationOperationHelper.processStepsOnStream(List.of(filter(single)), source.stream());

        assertEquals(plain.size(), conjoined.size());
        assertEquals(2, conjoined.size());
    }

    private static JsonObject vectorDoc(String id, String vector) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add("embedding", new JsonVector(vector));
        return obj;
    }

    private static BaseAggregationStep nearest(int k) {
        final var query = new JsonVector("#vector(1.0,0.0)");
        final var args = new JsonObject();
        args.add("value", query);
        args.add("k", new JsonNumber((double) k));
        return filter(new CustomOperator("nearest", "embedding", query, args));
    }

    @Test
    public void test_nearest_breaks_score_ties_on_id_regardless_of_input_order() throws IOException {
        final var forward = new ArrayList<>(List.of(vectorDoc("b", "#vector(2.0,0.0)"),
                vectorDoc("a", "#vector(3.0,0.0)"), vectorDoc("c", "#vector(0.0,1.0)")));
        final var backward = new ArrayList<>(forward.reversed());

        assertEquals(List.of("a"), run(forward, nearest(1)));
        assertEquals(List.of("a"), run(backward, nearest(1)));
    }

    @Test
    public void test_nearest_lists_tied_scores_in_id_order() throws IOException {
        final var source = List.of(vectorDoc("c", "#vector(5.0,0.0)"), vectorDoc("a", "#vector(1.0,0.0)"),
                vectorDoc("b", "#vector(2.0,0.0)"), vectorDoc("d", "#vector(0.0,1.0)"));

        assertEquals(List.of("a", "b", "c"), run(source, nearest(3)));
        assertEquals(List.of("a", "b", "c"), run(source.reversed(), nearest(3)));
    }
}
