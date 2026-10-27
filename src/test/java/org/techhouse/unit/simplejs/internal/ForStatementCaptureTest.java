package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.values.JsString;

public class ForStatementCaptureTest {
    private static boolean capturable(String source) {
        final var program = Parser.parse(Lexer.lexWithPositions(source), false);
        return ((ForStatement) program.getBody().getFirst()).bindingsCapturable();
    }

    private static String run(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_a_loop_without_closures_cannot_capture_its_bindings() {
        assertFalse(capturable("for (let i = 0; i < 3; i++) {}"));
        assertFalse(capturable("for (let i = 0; i < 3; i++) { let s = i * 2; s += 1; }"));
        assertFalse(capturable("for (let i = 0; i < 3; i++) { o.f(i); }"));
    }

    @Test
    public void test_any_closure_in_the_loop_makes_it_capturable() {
        assertTrue(capturable("for (let i = 0; i < 3; i++) { fs.push(() => i); }"));
        assertTrue(capturable("for (let i = 0, f = () => i; i < 3; i++) {}"));
        assertTrue(capturable("for (let i = 0; i < 3; i++) { fs.push(`${(() => i)()}`); }"));
        assertTrue(capturable("for (let i = 0; i < 3; i++) { fs.push(function () { return i; }); }"));
        assertTrue(capturable("for (let i = 0; i < 3; i++) { fs.push({ m() { return i; } }); }"));
        assertTrue(capturable("for (let i = 0; i < 3; i++) { fs.push(class { m() { return i; } }); }"));
        assertTrue(capturable("for (let i = 0; i < 3; f(() => i++)) {}"));
        assertTrue(capturable("for (let i = 0; i < 3; i++) { for (let j = 0; j < 1; j++) { fs.push(() => i); } }"));
    }

    @Test
    public void test_each_iteration_still_gets_its_own_binding_when_a_closure_can_see_it() {
        assertEquals("0,1,2", run(
                "const fs = []; for (let i = 0; i < 3; i++) { fs.push(() => i); }" + " fs.map(f => f()).join(',')"));
        assertEquals("0,1,2", run("const fs = []; for (let i = 0; i < 3; i++) { fs.push(`${i}`); }" + " fs.join(',')"));
        assertEquals("0", run("let first; for (let i = 0, f = () => i; i < 3; i++) { first ??= f; } String(first())"));
        assertEquals("3,3,3", run(
                "const fs = []; for (var i = 0; i < 3; i++) { fs.push(() => i); }" + " fs.map(f => f()).join(',')"));
    }

    @Test
    public void test_a_loop_without_closures_still_counts_correctly() {
        assertEquals("0,1,2,3,4", run("const out = []; for (let i = 0; i < 5; i++) { out.push(i); } out.join(',')"));
        assertEquals("6", run("let s = 0; for (const o = { n: 3 }; o.n > 0; o.n--) { s += o.n; } String(s)"));
    }

    @Test
    public void test_parameters_bind_simple_destructured_default_and_rest_forms() {
        assertEquals("1|2|undefined", run("function f(a, b, c) { return [a, b, c].map(String).join('|'); } f(1, 2)"));
        assertEquals("2", run("function f(a, a2) { return String(arguments.length); } f(1, 2)"));
        assertEquals("1,2,[3,4]", run("function f({ x }, [y], ...rest) { return `${x},${y},${JSON.stringify(rest)}`; }"
                + " f({ x: 1 }, [2], 3, 4)"));
        assertEquals("5,9", run("function f(a = 5, b = a + 4) { return `${a},${b}`; } f()"));
        assertEquals("7", run("const g = (n) => n + 1; String(g(6))"));
        assertEquals("x", run("function f(a, b) { 'use strict'; return typeof a === 'string' ? a : 'no'; } f('x')"));
    }
}
