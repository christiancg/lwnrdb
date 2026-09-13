package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.test.TestUtils;

public class FilterConjunctionDedupTest {
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

    private static BaseAggregationStep conjunction(ConjunctionOperatorType type) {
        return new FilterAggregationStep(
                new ConjunctionOperator(type, List.of(eq("role", "admin"), eq("active", "yes"))));
    }

    private static List<JsonObject> run(List<JsonObject> source, BaseAggregationStep step) throws IOException {
        return AggregationOperationHelper.processStepsOnStream(List.of(step), source.stream());
    }

    @Test
    public void test_or_emits_a_matching_document_once() throws IOException {
        final var source = List.of(doc("a", "admin", "yes"), doc("b", "admin", "no"), doc("c", "user", "yes"),
                doc("d", "user", "no"));

        final var result = run(source, conjunction(ConjunctionOperatorType.OR));

        assertEquals(List.of("a", "b", "c"),
                result.stream().map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).sorted().toList(),
                "a document matching both OR branches must be emitted once");
    }

    @Test
    public void test_or_keeps_the_first_occurrence_order() throws IOException {
        final var source = List.of(doc("a", "admin", "yes"), doc("b", "user", "yes"));

        final var result = run(source, conjunction(ConjunctionOperatorType.OR));

        assertEquals(List.of("a", "b"),
                result.stream().map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).toList(),
                "OR must keep first-occurrence order, as distinct() did");
    }

    @Test
    public void test_or_falls_back_to_content_dedup_without_an_id() throws IOException {
        final var source = List.of(doc(null, "admin", "yes"), doc(null, "admin", "yes"), doc(null, "user", "yes"));

        final var result = run(source, conjunction(ConjunctionOperatorType.OR));

        assertEquals(2, result.size(), "documents without _id must still deduplicate on content");
    }

    @Test
    public void test_and_emits_each_matching_document_once() throws IOException {
        final var source = List.of(doc("a", "admin", "yes"), doc("b", "admin", "no"));

        final var result = run(source, conjunction(ConjunctionOperatorType.AND));

        assertEquals(List.of("a"),
                result.stream().map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).toList());
    }

    @Test
    public void test_xor_emits_each_matching_document_once() throws IOException {
        final var source = List.of(doc("a", "admin", "yes"), doc("b", "admin", "no"), doc("c", "user", "yes"),
                doc("d", "user", "no"));

        final var result = run(source, conjunction(ConjunctionOperatorType.XOR));

        assertEquals(List.of("b", "c"),
                result.stream().map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).sorted().toList());
    }

    @Test
    public void test_nand_and_nor_emit_each_document_once() throws IOException {
        final var source = List.of(doc("a", "admin", "yes"), doc("b", "admin", "no"), doc("c", "user", "yes"));

        final var nand = run(source, conjunction(ConjunctionOperatorType.NAND));
        final var nor = run(source, conjunction(ConjunctionOperatorType.NOR));

        assertEquals(List.of("b", "c"),
                nand.stream().map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).sorted().toList());
        assertEquals(List.of(), nor.stream().map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).toList());
    }

    @Test
    public void test_and_without_an_id_is_still_refused() {
        final var source = List.of(doc(null, "admin", "yes"));

        assertThrows(IllegalStateException.class, () -> run(source, conjunction(ConjunctionOperatorType.AND)),
                "AND grouping still requires _id");
    }
}
