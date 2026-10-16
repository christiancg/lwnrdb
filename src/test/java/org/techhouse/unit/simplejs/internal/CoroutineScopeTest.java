package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class CoroutineScopeTest {
    private static ScriptResult run(String source) {
        final var limits = new ResourceLimits(-1, 10_000L, 200, true, false, List.of(), -1, -1, false, true,
                ResourceLimits.DEFAULT_MAX_MODULE_DEPTH);
        return new SimpleJs().run(source, new SimpleHostBindings(new JsonObject(), null, null, limits));
    }

    private static String text(String source) {
        final var result = run(source);
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        return result.getValue().asJsonString().getValue();
    }

    private static double number(String source) {
        final var result = run(source);
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        return result.getValue().asJsonNumber().getValue().doubleValue();
    }

    private static void assertSyntaxError(String source) {
        final var result = run(source);
        assertTrue(result.isError(), "expected a SyntaxError");
        assertEquals("SyntaxError", result.getErrorName(), result.getErrorMessage());
    }

    @Test
    public void awaitInsideAnArrowInsideAnAsyncFunctionIsASyntaxOrRuntimeErrorAsToday() {
        assertSyntaxError(
                "async function f() { const h = () => await Promise.resolve(3); return h(); }" + " return await f();");
        assertSyntaxError("async function f() { class A { x = await Promise.resolve(1) } return new A().x; }"
                + " return await f();");
        assertSyntaxError("async function f() { class A { static { await 1; } } return 1; } return await f();");
    }

    @Test
    public void yieldResolvesToTheNearestGenerator() {
        assertEquals("i,o", text("function* outer() { function* inner() { yield 'i'; } yield* inner(); yield 'o'; }"
                + " return [...outer()].join(',');"));
        assertEquals("3", text("function* g(a = 1) { var b = 2; yield a + b; } return [...g()].join(',');"));
        assertSyntaxError("function* g() { class A { x = yield 1 } yield new A().x; } return [...g()].join(',');");
    }

    @Test
    public void topLevelAwaitResolvesToTheModuleCoroutine() {
        assertEquals(7, number("const v = await Promise.resolve(7); return v;"));
        assertEquals("1,2", text("async function* ag() { yield 1; return Promise.resolve(2); } const it = ag();"
                + " const a = await it.next(); const b = await it.next(); return a.value + ',' + b.value;"));
    }

    @Test
    public void anImportedModuleSeesItsImportersCoroutine() {
        assertEquals(9, number("import script from 'script';"
                + " return script.importText('export default await Promise.resolve(9);').default;"));
        assertEquals(4,
                number("import script from 'script'; async function f() {"
                        + " return script.importText('export default await Promise.resolve(4);').default; }"
                        + " return await f();"));
        assertEquals(5,
                number("import script from 'script'; function p() {"
                        + " return script.importText('export default await Promise.resolve(5);', 'm5').default; }"
                        + " async function f() { return p(); } return await f();"));
    }
}
