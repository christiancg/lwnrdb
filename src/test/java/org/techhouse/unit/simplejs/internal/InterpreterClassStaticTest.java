package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class InterpreterClassStaticTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // Static methods and fields live on the class value
    @Test
    public void test_static_method_and_field() {
        assertEquals(42, num("class A { static answer = 42; } A.answer"));
        assertEquals(9, num("class A { static square(n) { return n * n; } } A.square(3)"));
    }

    // A static block runs in source order and can mutate static state
    @Test
    public void test_static_block() {
        final var source = """
                class A {
                    static total = 1;
                    static { A.total = A.total + 4; }
                    static { A.total = A.total * 2; }
                }
                A.total
                """;
        assertEquals(10, num(source));
    }

    // Static getters and setters dispatch through the class accessor tables
    @Test
    public void test_static_accessors() {
        final var source = """
                class Config {
                    static _v = 1;
                    static get v() { return Config._v; }
                    static set v(x) { Config._v = x + 1; }
                }
                Config.v = 10;
                Config.v
                """;
        assertEquals(11, num(source));
    }

    // A static private field is readable and writable from the class body
    @Test
    public void test_static_private_field() {
        assertEquals(1, num("class A { static #x = 1; static read() { return A.#x } } A.read()"));
        assertEquals(5, num("class A { static #x = 1; static bump() { A.#x = 5; return this.#x } } A.bump()"));
        assertTrue(bool("class A { static #x; static isUndefined() { return A.#x === undefined } } A.isUndefined()"));
    }

    // A static private method is callable via the class and via this
    @Test
    public void test_static_private_method() {
        assertEquals(7, num("class A { static #m() { return 7 } static call() { return A.#m() } } A.call()"));
        assertEquals(7, num("class A { static #m() { return 7 } static call() { return this.#m() } } A.call()"));
    }

    // Static private accessors route through their getter and setter
    @Test
    public void test_static_private_accessors() {
        assertEquals(3, num("class A { static #v = 3; static get #x() { return A.#v }"
                + " static read() { return A.#x } } A.read()"));
        assertEquals(9, num("class A { static #v = 0; static set #x(n) { A.#v = n }"
                + " static write() { A.#x = 9; return A.#v } } A.write()"));
    }

    // A brand check sees a static private name on the class
    @Test
    public void test_static_private_brand_check() {
        assertTrue(bool("class A { static #x = 1; static check(o) { return #x in o } } A.check(A)"));
        assertFalse(bool("class A { static #x = 1; static check(o) { return #x in o } } A.check({})"));
    }

    // Another class cannot even name a private member it does not declare
    @Test
    public void test_static_private_is_not_shared() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter
                .run("class A { static #x = 1 } class B { static probe() { return A.#x } } B.probe()"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter
                .run("class A { static #m() {} } class B { static probe() { return A.#m() } } B.probe()"));
    }

    // A static block can use static private state
    @Test
    public void test_static_block_with_static_private() {
        assertEquals(4, num("class A { static #x = 2; static out; static { A.out = A.#x * 2 } } A.out"));
    }

    // A computed static field key evaluating to a symbol is stored on the static symbol table
    @Test
    public void test_static_field_computed_symbol_key() {
        assertEquals(1, num("class A { static [Symbol.for('k')] = 1; } A[Symbol.for('k')]"));
    }

    // A computed static field key that coerces to a string is stored as a normal static prop
    @Test
    public void test_static_field_computed_string_key() {
        assertEquals(2, num("class A { static [1 + 1] = 2; } A['2']"));
    }

    // A static block is a break boundary: the enclosing iteration statement is out of its reach
    @Test
    public void test_static_block_break_is_syntax_error() {
        final var source = """
                while (false) {
                    class A {
                        static {
                            break;
                        }
                    }
                }
                """;
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run(source));
    }

    // static members are real own properties of the class object
    @Test
    public void test_class_statics_are_own_properties() {
        assertTrue(bool("class C { static m() {} } Object.prototype.hasOwnProperty.call(C, 'm')"));
        assertEquals(1, num("class C { static p = 1; } Object.keys(C).length"));
        assertTrue(bool("class C { static m() {} } Object.getOwnPropertyDescriptor(C, 'm').enumerable === false"));
    }

    // An explicit static "name" member (method, accessor or field) takes precedence over the
    // inferred class-expression name - the anonymous-class NamedEvaluation must not clobber it
    @Test
    public void test_explicit_static_name_member_beats_inferred_name() {
        assertEquals("function", str("const X = class { static name() {} }; typeof X.name"));
        assertEquals("string", str("const X = class { static name = 'explicit'; }; typeof X.name"));
        assertEquals("explicit", str("const X = class { static name = 'explicit'; }; X.name"));
    }

    // Each class static block is its own function-like scope for `var`: a var declared inside one
    // block neither leaks into the enclosing scope nor into a sibling static block
    @Test
    public void test_static_block_var_is_scoped_to_its_own_block() {
        final var source = """
                var test262 = 'outer scope';
                var probe1, probe2;
                class C {
                    static { var test262 = 'first block'; probe1 = test262; }
                    static { var test262 = 'second block'; probe2 = test262; }
                }
                JSON.stringify([test262, probe1, probe2])
                """;
        assertEquals("[\"outer scope\",\"first block\",\"second block\"]", str(source));
    }

    // A class function inherits the poisoned caller/arguments accessor pair from
    // %Function.prototype% just like any ordinary function: neither a base nor a derived class has
    // its own "caller"/"arguments", and assigning to either must throw rather than silently create a
    // new own static property (the inherited-accessor check setMember's JsClass branch used to skip).
    @Test
    public void test_class_static_caller_and_arguments_are_poisoned_inherited_accessors() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("class C {} C.caller = {}"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("class C {} C.arguments = {}"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("class B {} class D extends B {} D.caller = {}"));
        assertFalse(bool("class C {} C.hasOwnProperty('caller')"));
    }

    // A plain own static property write still lands normally alongside the poisoned pair.
    @Test
    public void test_class_static_ordinary_property_write_still_works() {
        assertEquals(5, num("class C {} C.x = 5; C.x"));
    }
}
