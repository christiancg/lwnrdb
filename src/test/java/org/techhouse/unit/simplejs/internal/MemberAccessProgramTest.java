package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class MemberAccessProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // An accessor installed on RegExp.prototype resolves on a regex instance
    @Test
    public void test_accessor_on_regexp_prototype() {
        assertEquals(42, num("""
                Object.defineProperty(RegExp.prototype, 'probe', { get() { return 42; } });
                /x/.probe
                """));
    }

    // An accessor installed on Array.prototype resolves on an array
    @Test
    public void test_accessor_on_array_prototype() {
        assertEquals(3, num("""
                Object.defineProperty(Array.prototype, 'probe', { get() { return 3; } });
                [1].probe
                """));
    }

    // An accessor installed on a typed array prototype resolves on a view
    @Test
    public void test_accessor_on_typed_array_prototype() {
        assertEquals(4, num("""
                Object.defineProperty(Int8Array.prototype, 'probe', { get() { return 4; } });
                new Int8Array(1).probe
                """));
    }

    // An accessor installed on Promise.prototype resolves on a promise
    @Test
    public void test_accessor_on_promise_prototype() {
        assertEquals(7, num("""
                Object.defineProperty(Promise.prototype, 'probe', { get() { return 7; } });
                Promise.resolve(1).probe
                """));
    }

    // An accessor defined on the arguments object is read and written through
    @Test
    public void test_accessor_on_the_arguments_object() {
        assertEquals("5,9", str("""
                function f() {
                    Object.defineProperty(arguments, 'probe', { get() { return 5; }, set(v) { this.seen = v; } });
                    arguments.probe = 9;
                    return arguments.probe + ',' + arguments.seen;
                }
                f()
                """));
    }

    // An accessor with no setter drops a write to the arguments object
    @Test
    public void test_getter_only_accessor_on_arguments() {
        assertEquals(5, num("""
                function f() {
                    Object.defineProperty(arguments, 'probe', { get() { return 5; } });
                    try { arguments.probe = 9; } catch (e) {}
                    return arguments.probe;
                }
                f()
                """));
    }

    // An index of the arguments object can be deleted
    @Test
    public void test_delete_an_arguments_index() {
        assertEquals("undefined", str("function f() { delete arguments[0]; return String(arguments[0]); } f(1)"));
    }

    // for-in over the arguments object enumerates its indices
    @Test
    public void test_for_in_over_arguments() {
        assertEquals("0,1", str("""
                function f() {
                    const keys = [];
                    for (const k in arguments) keys.push(k);
                    return keys.join(',');
                }
                f(1, 2)
                """));
    }

    // for-in over a class enumerates its static fields
    @Test
    public void test_for_in_over_a_class() {
        assertEquals("a,b", str("""
                class C { static a = 1; static b = 2; }
                const keys = [];
                for (const k in C) keys.push(k);
                keys.join(',')
                """));
    }

    // A static field can be deleted from a class
    @Test
    public void test_delete_a_static_field() {
        assertEquals("undefined", str("class C { static a = 1; } delete C.a; String(C.a)"));
    }

    // A static method can be deleted from a class
    @Test
    public void test_delete_a_static_method() {
        assertTrue(bool("class C { static m() {} } delete C.m"));
    }

    // A symbol-keyed static field can be deleted from a class
    @Test
    public void test_delete_a_static_symbol_field() {
        assertTrue(bool("const key = Symbol('k'); class C { static [key] = 1; } delete C[key]"));
    }

    // Deleting a symbol-keyed property of a frozen object is a TypeError
    @Test
    public void test_delete_a_symbol_of_a_frozen_object() {
        final var source = """
                const key = Symbol('k');
                const o = {};
                o[key] = 1;
                Object.freeze(o);
                delete o[key]
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    // A symbol-keyed instance setter of a class receives the written value
    @Test
    public void test_symbol_keyed_instance_setter() {
        assertEquals(4, num("""
                const key = Symbol('k');
                let seen = 0;
                class C { set [key](v) { seen = v; } }
                new C()[key] = 4;
                seen
                """));
    }

    // A generator's return finishes it with the given value
    @Test
    public void test_generator_return() {
        assertEquals("true,9", str("""
                function* g() { yield 1; }
                const result = g().return(9);
                String(result.done) + ',' + String(result.value)
                """));
    }

    // A generator's throw is observable inside its body
    @Test
    public void test_generator_throw() {
        assertEquals("caught:x", str("""
                function* g() { try { yield 1; } catch (e) { yield 'caught:' + e; } }
                const it = g();
                it.next();
                it.throw('x').value
                """));
    }

    // A labeled while loop can be broken out of
    @Test
    public void test_labeled_while() {
        assertEquals(3, num("let i = 0; outer: while (true) { i++; if (i > 2) break outer; } i"));
    }

    // A labeled do-while loop can be continued
    @Test
    public void test_labeled_do_while() {
        assertEquals(3, num("let i = 0; outer: do { i++; if (i > 1) continue outer; } while (i < 3); i"));
    }

    // A labeled for-in loop can be continued
    @Test
    public void test_labeled_for_in() {
        assertEquals("a,b", str("""
                const keys = [];
                outer: for (const k in { a: 1, b: 2 }) { keys.push(k); continue outer; }
                keys.join(',')
                """));
    }

    // A labeled switch can be broken out of
    @Test
    public void test_labeled_switch() {
        assertEquals("one", str("let out = 'x'; outer: switch (1) { case 1: out = 'one'; break outer; } out"));
    }

    // A labeled outer for-of can be continued from an inner loop
    @Test
    public void test_labeled_for_of_continue_from_inner_loop() {
        assertEquals(6, num("""
                let sum = 0;
                outer: for (const v of [1, 2, 3]) {
                    for (const w of [1]) { sum += v; continue outer; }
                }
                sum
                """));
    }

    // An optional call on a nullish member short-circuits the whole chain
    @Test
    public void test_optional_call_on_a_nullish_member() {
        assertEquals("undefined", str("const o = { a: null }; String(o.a?.b())"));
    }

    // A private method can be called through an optional chain
    @Test
    public void test_optional_private_method_call() {
        assertEquals(5, num("class C { #m() { return 5; } run(o) { return o?.#m(); } } new C().run(new C())"));
    }

    // A computed getter in an object literal is installed under the computed key
    @Test
    public void test_computed_getter_in_an_object_literal() {
        assertEquals(11, num("const k = 'x'; const o = { get [k]() { return 11; } }; o.x"));
    }

    // A computed setter in an object literal receives the written value
    @Test
    public void test_computed_setter_in_an_object_literal() {
        assertEquals(3, num("""
                const k = 'x';
                let seen = 0;
                const o = { set [k](v) { seen = v; } };
                o.x = 3;
                seen
                """));
    }

    // Spreading a string uses a patched String.prototype iterator
    @Test
    public void test_string_spread_uses_a_patched_iterator() {
        assertEquals("z", str("""
                String.prototype[Symbol.iterator] = function* () { yield 'z'; };
                [...'ab'].join('')
                """));
    }

    // A postfix increment on an object coerces it through valueOf
    @Test
    public void test_postfix_increment_on_an_object() {
        assertEquals("4,5", str("let o = { valueOf() { return 4; } }; const before = o++; before + ',' + o"));
    }

    // A prefix increment on an object reports the incremented number
    @Test
    public void test_prefix_increment_on_an_object() {
        assertEquals(5, num("let o = { valueOf() { return 4; } }; ++o"));
    }
}
