package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.host.DatabaseAccess;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.internal.Session;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;

public class SuppressedErrorRealmTest {
    private static final String OUTER = "export default () => {"
            + " try { using a = { [Symbol.dispose]() { throw new Error('a'); } },"
            + " b = { [Symbol.dispose]() { throw new Error('b'); } }; }"
            + " catch (e) { return e instanceof SuppressedError && Object.getPrototypeOf(e) === SuppressedError.prototype; }"
            + " return false; };";

    private record Host() implements HostBindings {
        @Override
        public JsonObject args() {
            return new JsonObject();
        }

        @Override
        public DatabaseAccess database() {
            return null;
        }

        @Override
        public Consumer<String> console() {
            return null;
        }

        @Override
        public ResourceLimits limits() {
            return ResourceLimits.unlimited();
        }
    }

    private static Session open(String source) {
        return Interpreter.open(Parser.parse(Lexer.lexWithPositions(source)), new Host());
    }

    @Test
    public void aNestedInterpreterDoesNotChangeTheOuterScriptsSuppressedError() {
        try (var outer = open(OUTER)) {
            try (var inner = open("1")) {
                assertEquals(1.0, ((JsNumber) inner.outcome().lastValue()).getValue());
            }
            assertEquals(JsBoolean.TRUE, outer.call(outer.outcome().exportDefault(), List.of()));
        }
    }
}
