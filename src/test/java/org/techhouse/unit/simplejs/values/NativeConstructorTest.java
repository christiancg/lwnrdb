package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public class NativeConstructorTest {
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

    private static JsNativeFunction recording(JsValue[] seen) {
        final var function = new JsNativeFunction("Recorder", (_, _, newTarget) -> {
            seen[0] = newTarget;
            return new JsObject();
        });
        function.markConstructor();
        return function;
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    @Test
    public void newTargetIsUndefinedForAPlainCall() {
        final var seen = new JsValue[1];
        recording(seen).invoke(JsUndefined.getInstance(), List.of());
        assertSame(JsUndefined.getInstance(), seen[0]);
    }

    @Test
    public void newTargetIsTheConstructorForNew() {
        final var seen = new JsValue[1];
        final var function = recording(seen);
        try (var session = Interpreter.open(Parser.parse(Lexer.lexWithPositions("1")), new Host())) {
            session.interpreter.construct(function, List.of(), function);
        }
        assertSame(function, seen[0]);
    }

    @Test
    public void reflectConstructPassesTheGivenNewTarget() {
        assertTrue(bool("class B {} const e = Reflect.construct(Error, ['m'], B);"
                + " Object.getPrototypeOf(e) === B.prototype && e.message === 'm'"));
        assertTrue(bool("class B {} Object.getPrototypeOf(Reflect.construct(Date, [0], B)) === B.prototype"));
    }

    @Test
    public void aSubclassOfABuiltinGetsItsOwnPrototype() {
        assertTrue(bool("class M extends Map { size2() { return this.size * 2; } }"
                + " const m = new M([[1, 2]]); m instanceof M && m instanceof Map && m.size2() === 2"));
        assertTrue(bool("class E extends TypeError {} const e = new E('x');"
                + " e instanceof E && e instanceof TypeError && e.message === 'x'"));
    }
}
