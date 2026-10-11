package org.techhouse.unit.simplejs;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.ScriptCallableException;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class ScriptCallableGetterTest {
    private final SimpleJs simpleJs = new SimpleJs();

    private static SimpleHostBindings host(ResourceLimits limits) {
        return new SimpleHostBindings(new JsonObject(), null, null, limits);
    }

    private static JsonObject document(double a, double b) {
        final var document = new JsonObject();
        document.add("a", new JsonNumber(a));
        document.add("b", new JsonNumber(b));
        return document;
    }

    private static double numberAt(JsonObject object, String field) {
        return object.get(field).asJsonNumber().getValue().doubleValue();
    }

    @Test
    public void applyReadsAGetterOnTheReturnedObject() {
        try (var callable = simpleJs.openCallable(
                "export default (doc) => ({ get total() { return doc.a + doc.b; } });",
                host(ResourceLimits.unlimited()))) {
            final var result = callable.apply(document(1, 2)).asJsonObject();
            assertEquals(3d, numberAt(result, "total"));
        }
    }

    @Test
    public void applyWithContextReadsAGetter() {
        try (var callable = simpleJs.openCallable(
                "export default (doc, ctx) => ({ ...doc, get event() { return ctx.event; } });",
                host(ResourceLimits.unlimited()))) {
            final var context = new JsonObject();
            context.add("event", new JsonString("CREATED"));
            final var result = callable.applyWithContext(document(1, 2), context).asJsonObject();
            assertEquals("CREATED", result.get("event").asJsonString().getValue());
            assertEquals(1d, numberAt(result, "a"));
        }
    }

    @Test
    public void applyAccumulatorReadsAGetter() {
        try (var callable = simpleJs.openCallable(
                "export default (acc, doc) => { const sum = (acc?.sum ?? 0) + doc.a; return { get sum() { return sum; } }; };",
                host(ResourceLimits.unlimited()))) {
            var accumulator = callable.apply(JsonNull.INSTANCE, document(2, 0));
            accumulator = callable.apply(accumulator, document(5, 0));
            assertEquals(7d, numberAt(accumulator.asJsonObject(), "sum"));
        }
    }

    @Test
    public void aThrowingGetterSurfacesAsAScriptCallableException() {
        try (var callable = simpleJs.openCallable(
                "export default (doc) => ({ get total() { throw new TypeError('no total'); } });",
                host(ResourceLimits.unlimited()))) {
            final var failure = assertThrows(ScriptCallableException.class, () -> callable.apply(document(1, 2)));
            assertEquals("TypeError", failure.getErrorName());
        }
    }

    @Test
    public void aGetterIsChargedToTheRunBudget() {
        try (var callable = simpleJs.openCallable(
                "export default (doc) => ({ get total() { let n = 0; for (;;) { n++; } } });",
                host(new ResourceLimits(3000, -1, -1)))) {
            final var failure = assertThrows(ScriptCallableException.class, () -> callable.apply(document(1, 2)));
            assertEquals("ScriptLimitError", failure.getErrorName());
        }
    }
}
