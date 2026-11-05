package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.utils.FieldPath;
import org.techhouse.utils.SaltedKey;

public class AggregationDistinctTest {
    private static JsonObject document(String id, String field, JsonBaseElement value) {
        final var doc = new JsonObject();
        doc.add("_id", new JsonString(id));
        if (value != null) {
            final var segments = field.split("\\.");
            var parent = doc;
            for (var i = 0; i < segments.length - 1; i++) {
                final var child = new JsonObject();
                parent.add(segments[i], child);
                parent = child;
            }
            parent.add(segments[segments.length - 1], value);
        }
        return doc;
    }

    private static List<JsonObject> distinct(String field, List<JsonObject> documents) throws Exception {
        final List<BaseAggregationStep> steps = List.of(new DistinctAggregationStep(field));
        return AggregationOperationHelper.processStepsOnStream(steps, documents.stream());
    }

    private static JsonArray array(JsonBaseElement... elements) {
        final var array = new JsonArray();
        for (final var element : elements) {
            array.add(element);
        }
        return array;
    }

    private static JsonObject objectOfX(JsonBaseElement value) {
        final var object = new JsonObject();
        object.add("x", value);
        return object;
    }

    @Test
    public void test_distinct_builds_one_row_per_new_value() throws Exception {
        for (final var field : List.of("cat", "a.b")) {
            final var documents = new ArrayList<JsonObject>();
            final List<JsonBaseElement> firstSeen = new ArrayList<>();
            for (var i = 0; i < 300; i++) {
                final var value = new JsonString("v" + (i % 3));
                if (i < 3) {
                    firstSeen.add(value);
                }
                documents.add(document("d" + i, field, value));
            }
            final var rows = distinct(field, documents);
            assertEquals(3, rows.size());
            for (var i = 0; i < 3; i++) {
                final var row = rows.get(i);
                final var leaf = field.contains(".") ? row.get("a").asJsonObject() : row;
                assertEquals(1, row.size());
                assertEquals(1, leaf.size());
                assertSame(firstSeen.get(i), leaf.get(field.contains(".") ? "b" : field));
            }
        }
    }

    @Test
    public void test_distinct_result_is_unchanged_for_mixed_kinds() throws Exception {
        final List<JsonBaseElement> values = new ArrayList<>(List.of(new JsonNumber(1), new JsonString("1"),
                JsonBoolean.TRUE, JsonNull.INSTANCE, new JsonNumber(1.0), new JsonNumber(0.0), new JsonNumber(-0.0),
                new JsonString("A"), new JsonString("a"), array(new JsonNumber(1), new JsonNumber(2)),
                array(new JsonNumber(2), new JsonNumber(1)), array(new JsonNumber(1), new JsonNumber(2)),
                objectOfX(new JsonNumber(1)), objectOfX(new JsonNumber(1.0)), JsonBoolean.FALSE,
                new JsonString("#geo(1.0,2.0)"), new JsonString("#geo(1.0,2.0)"), JsonNull.INSTANCE));
        values.add(null);
        for (final var field : List.of("cat", "a.b")) {
            final var documents = new ArrayList<JsonObject>();
            for (var i = 0; i < values.size(); i++) {
                documents.add(document("m" + i, field, values.get(i)));
            }
            final var rows = distinct(field, documents);
            assertEquals(12, rows.size());
            assertEquals(rowKeyedDistinct(field, documents).toString(), rows.toString());
        }
    }

    private static List<JsonObject> rowKeyedDistinct(String field, List<JsonObject> documents) {
        final var path = FieldPath.of(field);
        final var seen = new HashSet<SaltedKey>();
        final var rows = new ArrayList<JsonObject>();
        for (final var document : documents) {
            if (path.isIn(document)) {
                final var row = new JsonObject();
                path.set(row, path.valueIn(document));
                if (seen.add(new SaltedKey(row))) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }
}
