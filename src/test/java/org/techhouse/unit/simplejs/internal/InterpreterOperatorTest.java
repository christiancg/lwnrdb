package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;

public class InterpreterOperatorTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // Logical operators short-circuit and return operand values
    @Test
    public void test_logical_operators() {
        assertEquals(5, num("0 || 5"));
        assertEquals(2, num("1 && 2"));
        assertEquals(3, num("null ?? 3"));
        assertEquals(0, num("0 ?? 3"));
    }

    // typeof reports value kinds and treats undeclared identifiers as undefined
    @Test
    public void test_typeof() {
        assertEquals("number", str("typeof 1"));
        assertEquals("string", str("typeof 'a'"));
        assertEquals("bigint", str("typeof 1n"));
        assertEquals("object", str("typeof null"));
        assertEquals("undefined", str("typeof notDeclared"));
    }

    // Prefix and postfix update expressions return the right value and mutate the binding
    @Test
    public void test_update_expressions() {
        assertEquals(6, num("let x = 5; ++x"));
        assertEquals(5, num("let x = 5; x++"));
        assertEquals(6, num("let x = 5; x++; x"));
        assertEquals(4, num("let x = 5; --x"));
    }

    // Optional chaining short-circuits on a nullish receiver
    @Test
    public void test_optional_chaining() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("let o = null; o?.a"));
        assertEquals(1, num("let o = { a: 1 }; o?.a"));
    }

    // A nullish link short-circuits the whole rest of the chain, not just its own access
    @Test
    public void test_optional_chaining_short_circuit_propagation() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("let a = null; a?.b.c"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("let a = null; a?.b.c.d"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("let a = null; a?.b[c].d"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("let a = { b: null }; a.b?.c.d"));
        assertEquals(3, num("let a = { b: { c: { d: 3 } } }; a?.b.c.d"));
    }

    // An optional call does not evaluate its callee's arguments when the chain short-circuits
    @Test
    public void test_optional_chaining_call_short_circuit() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("let a = null; a?.b()"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("let a = null; a?.b().c"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("let a = null; a?.()"));
        assertInstanceOf(JsUndefined.class,
                Interpreter.run("let calls = 0; let a = null; a?.b(calls = 1); calls === 1 ? 9 : undefined"));
        assertEquals(5, num("let o = { b() { return 5; } }; o?.b()"));
    }

    // Non-optional access after a short-circuited link still throws when reached directly on nullish
    @Test
    public void test_optional_chaining_non_optional_still_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("let a = { b: null }; a.b.c"));
    }

    // Update expressions mutate member targets and BigInt bindings
    @Test
    public void test_update_member_and_bigint() {
        assertEquals(6, num("let o = { a: 5 }; o.a++; o.a"));
        assertEquals(6, num("let o = { a: 5 }; ++o.a"));
        assertEquals(BigInteger.valueOf(2), ((JsBigInt) Interpreter.run("let x = 1n; x++; x")).getValue());
        assertEquals(BigInteger.valueOf(5), ((JsBigInt) Interpreter.run("let x = 5n; x++")).getValue());
    }

    // switch matches by strict equality, falls through, and honours break
    @Test
    public void test_switch_matching() {
        assertEquals(10, num("let r = 0; switch (1) { case 1: r = 10; break; case 2: r = 20; } r"));
        assertEquals(30, num("let r = 0; switch (1) { case 1: r += 10; case 2: r += 20; } r"));
        assertEquals(99, num("let r = 0; switch (5) { case 1: r = 1; break; default: r = 99; } r"));
    }

    // A default clause in the middle is reached only when no case matches
    @Test
    public void test_switch_default_in_middle() {
        assertEquals(7, num("let r = 0; switch (9) { case 1: r = 1; break; default: r = 7; break; case 2: r = 2; } r"));
    }

    // continue inside a switch continues the enclosing loop
    @Test
    public void test_switch_continue_in_loop() {
        final var source = """
                let s = 0;
                for (let i = 0; i < 4; i++) {
                    switch (i) {
                        case 1: continue;
                        default: s += i;
                    }
                }
                s
                """;
        assertEquals(5, num(source));
    }

    // A labeled break inside a switch exits the labeled loop
    @Test
    public void test_switch_labeled_break() {
        final var source = """
                let s = 0;
                loop: for (let i = 0; i < 4; i++) {
                    switch (i) {
                        case 2: break loop;
                        default: s += i;
                    }
                }
                s
                """;
        assertEquals(1, num(source));
    }

    // let declarations inside cases share one switch block scope
    @Test
    public void test_switch_lexical_scope() {
        assertEquals(3, num("switch (1) { case 1: let x = 3; x; break; }; 3"));
    }

    // ToPrimitive honors valueOf in numeric contexts and toString in string contexts
    @Test
    public void test_to_primitive_value_of_and_to_string() {
        assertEquals(6, num("let o = { valueOf() { return 5; } }; o + 1"));
        assertEquals("xy", str("let o = { toString() { return 'x'; } }; o + 'y'"));
        assertEquals(9, num("+{ valueOf() { return 9; } }"));
        assertEquals(1, num("let o = { valueOf() { return 5; } }; o & 3"));
        assertTrue(bool("let o = { valueOf() { return 5; } }; o < 10"));
    }

    // @@toPrimitive takes precedence and receives the correct hint
    @Test
    public void test_symbol_to_primitive_hints() {
        final var src = "let o = { [Symbol.toPrimitive](hint) { return hint === 'number' ? 42 : 'str'; } };";
        assertEquals(42, num(src + " o * 1"));
        assertEquals("str", str(src + " `${o}`"));
        assertEquals("str!", str(src + " o + '!'"));
    }

    // Hint ordering: string context tries toString first, numeric context tries valueOf first
    @Test
    public void test_to_primitive_hint_ordering() {
        final var both = "let o = { toString() { return 'S'; }, valueOf() { return 7; } };";
        assertEquals("S", str(both + " `${o}`"));
        assertEquals(14, num(both + " o * 2"));
    }

    // Loose equality against a primitive coerces the object with the default hint
    @Test
    public void test_to_primitive_loose_equals() {
        assertTrue(bool("let o = { valueOf() { return 3; } }; o == 3"));
    }

    // A non-primitive valueOf result falls through to toString; two object results throw
    @Test
    public void test_to_primitive_fallthrough_and_error() {
        assertEquals("T", str("let o = { valueOf() { return {}; }, toString() { return 'T'; } }; o + ''"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("let o = { valueOf() { return {}; }, toString() { return {}; } }; o + ''"));
    }

    // Postfix update coerces the returned old value through valueOf
    @Test
    public void test_to_primitive_postfix_update() {
        assertEquals(5, num("let o = { x: { valueOf() { return 5; } } }; let r = o.x++; r"));
    }

    // Objects and arrays with no user hooks keep their default coercions
    @Test
    public void test_to_primitive_defaults_unchanged() {
        assertEquals("[object Object]", str("({}) + ''"));
        assertEquals("1,2", str("[1, 2] + ''"));
        assertTrue(Double.isNaN(num("({}) * 2")));
    }
}
