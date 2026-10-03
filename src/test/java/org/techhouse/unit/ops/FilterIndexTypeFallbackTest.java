package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;

public class FilterIndexTypeFallbackTest extends FilterResolutionSupport {
    @Test
    public void test_contains_falls_back_to_scan_on_a_mixed_type_field() throws IOException, InterruptedException {
        final var cache = mixedTypeFixture();
        addTyped(cache, "c1", "tags", new JsonString("alpha"));
        addTyped(cache, "c2", "tags", arrayOf(new JsonString("alpha"), new JsonString("beta")));
        final var scanned = matched(new FieldOperator(FieldOperatorType.CONTAINS, "tags", new JsonString("alpha")));
        indexField(cache, "tags");

        final var indexed = matched(new FieldOperator(FieldOperatorType.CONTAINS, "tags", new JsonString("alpha")));

        assertEquals(Set.of("c1", "c2"), indexed);
        assertEquals(scanned, indexed);
    }

    @Test
    public void test_not_in_falls_back_to_scan_on_a_mixed_type_field() throws IOException, InterruptedException {
        final var cache = mixedTypeFixture();
        addTyped(cache, "n1", "status", new JsonString("active"));
        addTyped(cache, "n2", "status", new JsonString("inactive"));
        addTyped(cache, "n3", "status", new JsonNumber(5));
        final var operand = arrayOf(new JsonString("active"));
        final var scanned = matched(new FieldOperator(FieldOperatorType.NOT_IN, "status", operand));
        indexField(cache, "status");

        final var indexed = matched(new FieldOperator(FieldOperatorType.NOT_IN, "status", operand));

        assertEquals(Set.of("n2", "n3"), indexed);
        assertEquals(scanned, indexed);
    }

    @Test
    public void test_contains_still_uses_the_index_on_a_homogeneous_field() throws IOException, InterruptedException {
        final var cache = mixedTypeFixture();
        addTyped(cache, "h1", "tags", new JsonString("alpha"));
        addTyped(cache, "h2", "tags", new JsonString("alphabet"));
        addTyped(cache, "h3", "tags", new JsonString("beta"));
        indexField(cache, "tags");

        assertEquals(Set.of("h1", "h2"),
                matched(new FieldOperator(FieldOperatorType.CONTAINS, "tags", new JsonString("alpha"))));
    }

    @Test
    public void test_contains_on_a_number_field_falls_back_to_scan() throws IOException, InterruptedException {
        final var cache = mixedTypeFixture();
        addTyped(cache, "a", "tags", new JsonNumber(5));
        addTyped(cache, "b", "tags", arrayOf(new JsonNumber(5), new JsonNumber(7)));
        final var operator = new FieldOperator(FieldOperatorType.CONTAINS, "tags", new JsonNumber(5));
        final var scanned = matched(operator);
        indexField(cache, "tags");

        final var indexed = matched(operator);

        assertEquals(Set.of("b"), indexed,
                "the CONTAINS scan predicate matches an array-valued document holding the operand, so a scalar"
                        + " index answering empty would silently drop it");
        assertEquals(scanned, indexed);
    }

    @Test
    public void test_contains_on_a_boolean_field_falls_back_to_scan() throws IOException, InterruptedException {
        final var cache = mixedTypeFixture();
        addTyped(cache, "a", "flags", new JsonBoolean(true));
        addTyped(cache, "b", "flags", arrayOf(new JsonBoolean(true)));
        final var operator = new FieldOperator(FieldOperatorType.CONTAINS, "flags", new JsonBoolean(true));
        final var scanned = matched(operator);
        indexField(cache, "flags");

        assertEquals(scanned, matched(operator));
    }

    @Test
    public void test_not_in_with_an_object_operand_falls_back_on_a_mixed_type_field()
            throws IOException, InterruptedException {
        final var cache = mixedTypeFixture();
        final var first = new JsonObject();
        first.add("a", new JsonNumber(1));
        final var second = new JsonObject();
        second.add("b", new JsonNumber(2));
        addTyped(cache, "d1", "tag", first);
        addTyped(cache, "d2", "tag", new JsonString("x"));
        addTyped(cache, "d3", "tag", second);
        final var operator = new FieldOperator(FieldOperatorType.NOT_IN, "tag", arrayOf(first));
        final var scanned = matched(operator);
        indexField(cache, "tag");

        final var indexed = matched(operator);

        assertEquals(Set.of("d2", "d3"), indexed,
                "a NOT_IN complement taken from the object hash index alone omits every document held by another"
                        + " index of the same field");
        assertEquals(scanned, indexed);
    }

    @Test
    public void test_not_in_with_an_array_operand_falls_back_on_a_mixed_type_field()
            throws IOException, InterruptedException {
        final var cache = mixedTypeFixture();
        final var first = arrayOf(new JsonNumber(1));
        addTyped(cache, "d1", "tag", first);
        addTyped(cache, "d2", "tag", new JsonString("x"));
        addTyped(cache, "d3", "tag", arrayOf(new JsonNumber(2)));
        final var operator = new FieldOperator(FieldOperatorType.NOT_IN, "tag", arrayOf(first));
        final var scanned = matched(operator);
        indexField(cache, "tag");

        final var indexed = matched(operator);

        assertEquals(Set.of("d2", "d3"), indexed);
        assertEquals(scanned, indexed);
    }

    @Test
    public void test_not_in_with_an_object_operand_still_uses_the_index_on_a_homogeneous_field()
            throws IOException, InterruptedException {
        final var cache = mixedTypeFixture();
        final var first = new JsonObject();
        first.add("a", new JsonNumber(1));
        final var second = new JsonObject();
        second.add("b", new JsonNumber(2));
        addTyped(cache, "d1", "tag", first);
        addTyped(cache, "d2", "tag", second);
        indexField(cache, "tag");

        final var indexed = matched(new FieldOperator(FieldOperatorType.NOT_IN, "tag", arrayOf(first)));

        assertEquals(Set.of("d2"), indexed);
    }
}
