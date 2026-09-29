package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.MapOperatorHelper;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;
import org.techhouse.test.TestUtils;

public class MapFilterAgreementTest {

    private static final String MARKER_FIELD = "marker";

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
    }

    private static JsonObject document(String id, boolean field1, boolean field2) {
        final var jsonObject = new JsonObject();
        jsonObject.addProperty(Globals.PK_FIELD, id);
        jsonObject.addProperty("field1", field1);
        jsonObject.addProperty("field2", field2);
        jsonObject.addProperty(MARKER_FIELD, "present");
        return jsonObject;
    }

    private static List<JsonObject> everyCombination() {
        return List.of(document("neither", false, false), document("first", true, false),
                document("second", false, true), document("both", true, true));
    }

    private static ConjunctionOperator conjunctionOf(ConjunctionOperatorType type) {
        final List<BaseOperator> leaves = List.of(
                new FieldOperator(FieldOperatorType.EQUALS, "field1", new JsonBoolean(true)),
                new FieldOperator(FieldOperatorType.EQUALS, "field2", new JsonBoolean(true)));
        return new ConjunctionOperator(type, leaves);
    }

    private static Set<String> idsSelectedByFilter(ConjunctionOperatorType type) throws IOException {
        try (var filtered = FilterOperatorHelper.processOperator(conjunctionOf(type), everyCombination().stream(), "",
                "")) {
            return filtered.map(jsonObject -> jsonObject.get(Globals.PK_FIELD).asJsonString().getValue())
                    .collect(LinkedHashSet::new, Set::add, Set::addAll);
        }
    }

    private static Set<String> idsSelectedByMapCondition(ConjunctionOperatorType type) {
        final var selected = new LinkedHashSet<String>();
        for (final var jsonObject : everyCombination()) {
            final var mapped = MapOperatorHelper
                    .processOperator(new RemoveFieldMapOperator(MARKER_FIELD, conjunctionOf(type)), jsonObject);
            if (!mapped.has(MARKER_FIELD)) {
                selected.add(jsonObject.get(Globals.PK_FIELD).asJsonString().getValue());
            }
        }
        return selected;
    }

    @Test
    public void test_a_map_condition_selects_exactly_what_the_same_filter_selects() throws IOException {
        final var disagreeing = new ArrayList<String>();
        for (final var type : ConjunctionOperatorType.values()) {
            final var byFilter = idsSelectedByFilter(type);
            final var byMapCondition = idsSelectedByMapCondition(type);
            if (!byFilter.equals(byMapCondition)) {
                disagreeing.add(
                        type + ": FILTER selected " + byFilter + " but the MAP condition selected " + byMapCondition);
            }
        }
        assertEquals(List.of(), disagreeing,
                "a conjunction must mean the same thing in a MAP condition as it does in a FILTER");
    }

    private static final String ARRAY_FIELD = "tags";

    private static JsonObject documentWith(String id, JsonBaseElement fieldValue) {
        final var jsonObject = new JsonObject();
        jsonObject.addProperty(Globals.PK_FIELD, id);
        jsonObject.add(ARRAY_FIELD, fieldValue);
        jsonObject.addProperty(MARKER_FIELD, "present");
        return jsonObject;
    }

    private static List<JsonObject> arrayOperandFixture() {
        return List.of(documentWith("array_value", arrayOperand()),
                documentWith("scalar_value", new JsonString("solo")), documentWith("null_value", JsonNull.INSTANCE));
    }

    private static JsonArray arrayOperand() {
        final var array = new JsonArray();
        array.add(new JsonString("a"));
        array.add(new JsonString("b"));
        return array;
    }

    private static Set<String> idsSelectedByFilter(BaseOperator condition, List<JsonObject> documents)
            throws IOException {
        try (var filtered = FilterOperatorHelper.processOperator(condition, documents.stream(), "", "")) {
            return filtered.map(jsonObject -> jsonObject.get(Globals.PK_FIELD).asJsonString().getValue())
                    .collect(LinkedHashSet::new, Set::add, Set::addAll);
        }
    }

    private static Set<String> idsSelectedByMapCondition(BaseOperator condition, List<JsonObject> documents) {
        final var selected = new LinkedHashSet<String>();
        for (final var jsonObject : documents) {
            final var mapped = MapOperatorHelper.processOperator(new RemoveFieldMapOperator(MARKER_FIELD, condition),
                    jsonObject);
            if (!mapped.has(MARKER_FIELD)) {
                selected.add(jsonObject.get(Globals.PK_FIELD).asJsonString().getValue());
            }
        }
        return selected;
    }

    @Test
    public void test_not_equals_array_operand_agrees_between_filter_and_map_condition() throws IOException {
        final var condition = new FieldOperator(FieldOperatorType.NOT_EQUALS, ARRAY_FIELD, arrayOperand());
        final var documents = arrayOperandFixture();
        final var expected = Set.of("scalar_value", "null_value");

        final var byFilter = idsSelectedByFilter(condition, documents);
        final var byMapCondition = idsSelectedByMapCondition(condition, documents);

        assertEquals(expected, byFilter);
        assertEquals(expected, byMapCondition);
    }
}
