package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;

public class AggregationSaltedKeyTest {
    private static JsonObject keyed(String id, JsonBaseElement key) {
        final var document = new JsonObject();
        document.add(Globals.PK_FIELD, new JsonString(id));
        document.add("k", key);
        return document;
    }

    private static JsonObject object(String firstKey, int firstValue, String secondKey, int secondValue) {
        final var object = new JsonObject();
        object.addProperty(firstKey, firstValue);
        object.addProperty(secondKey, secondValue);
        return object;
    }

    private static JsonArray array(JsonBaseElement... elements) {
        final var array = new JsonArray();
        for (var element : elements) {
            array.add(element);
        }
        return array;
    }

    private static int groups(JsonObject... documents) throws IOException {
        return AggregationOperationHelper
                .processStepsOnStream(List.of(new GroupByAggregationStep("k")), Stream.of(documents)).size();
    }

    private static int distinctValues(JsonObject... documents) throws IOException {
        return AggregationOperationHelper
                .processStepsOnStream(List.of(new DistinctAggregationStep("k")), Stream.of(documents)).size();
    }

    @Test
    public void equalKeysShareOneGroupWhateverTheirKind() throws IOException {
        final var documents = new JsonObject[]{keyed("1", new JsonNumber(0.0)), keyed("2", new JsonNumber(-0.0)),
                keyed("3", new JsonNumber(Double.NaN)), keyed("4", new JsonNumber(Double.NaN)),
                keyed("5", object("a", 1, "b", 2)), keyed("6", object("b", 2, "a", 1)),
                keyed("7", array(new JsonNumber(1), new JsonString("x"))),
                keyed("8", array(new JsonNumber(1), new JsonString("x"))), keyed("9", JsonBoolean.TRUE),
                keyed("10", JsonBoolean.TRUE), keyed("11", JsonNull.INSTANCE), keyed("12", JsonNull.INSTANCE),
                keyed("13", new JsonString("s")), keyed("14", new JsonString("s"))};
        assertEquals(7, groups(documents));
        assertEquals(7, distinctValues(documents));
    }

    @Test
    public void differentKeysStayApart() throws IOException {
        final var documents = new JsonObject[]{keyed("1", new JsonNumber(1)), keyed("2", new JsonString("1")),
                keyed("3", array(new JsonNumber(1), new JsonNumber(2))),
                keyed("4", array(new JsonNumber(2), new JsonNumber(1))), keyed("5", object("a", 1, "b", 2)),
                keyed("6", object("a", 2, "b", 1)), keyed("7", JsonBoolean.FALSE), keyed("8", new JsonString("Aa")),
                keyed("9", new JsonString("BB"))};
        assertEquals(9, groups(documents));
        assertEquals(9, distinctValues(documents));
    }

    @Test
    public void distinctRowsIgnoreIdAndMemberOrder() throws IOException {
        final var first = object("a", 1, "b", 2);
        first.add(Globals.PK_FIELD, new JsonString("1"));
        final var second = object("b", 2, "a", 1);
        second.add(Globals.PK_FIELD, new JsonString("2"));
        final var third = object("a", 1, "b", 3);
        third.add(Globals.PK_FIELD, new JsonString("3"));
        assertEquals(2, AggregationOperationHelper
                .processStepsOnStream(List.of(new DistinctAggregationStep(null)), Stream.of(first, second, third))
                .size());
    }
}
