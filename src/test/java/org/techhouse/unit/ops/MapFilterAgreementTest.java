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
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonObject;
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
}
