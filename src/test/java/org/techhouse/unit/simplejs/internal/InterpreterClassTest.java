package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class InterpreterClassTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // A basic class stores constructor-assigned state on the instance
    @Test
    public void test_basic_class_and_new() {
        assertEquals(5, num("class A { constructor(x) { this.x = x; } } new A(5).x"));
    }

    // Instance methods read this and return computed values
    @Test
    public void test_instance_method() {
        final var source = """
                class Adder {
                    constructor(base) { this.base = base; }
                    add(n) { return this.base + n; }
                }
                new Adder(10).add(5)
                """;
        assertEquals(15, num(source));
    }

    // Instance field initializers run at construction and can reference this
    @Test
    public void test_instance_field_initializer() {
        assertEquals(10, num("class A { n = 10; } new A().n"));
        assertEquals(20, num("class A { n = 10; doubled = this.n * 2; } new A().doubled"));
    }

    // Getters and setters dispatch through the class accessor tables
    @Test
    public void test_getter_setter() {
        final var source = """
                class Box {
                    constructor() { this._v = 0; }
                    get value() { return this._v; }
                    set value(v) { this._v = v * 2; }
                }
                let b = new Box();
                b.value = 5;
                b.value
                """;
        assertEquals(10, num(source));
    }

    // A derived class without a constructor forwards args to the base
    @Test
    public void test_default_constructor_forwards_args() {
        final var source = """
                class Base { constructor(x) { this.x = x; } }
                class Derived extends Base { }
                new Derived(7).x
                """;
        assertEquals(7, num(source));
    }

    // Class expressions produce a constructable value
    @Test
    public void test_class_expression() {
        assertEquals(3, num("const C = class { constructor() { this.v = 3; } }; new C().v"));
    }

    // A class can reference its own name from a static method
    @Test
    public void test_class_self_reference() {
        final var source = """
                class Registry {
                    static make() { return new Registry(); }
                    constructor() { this.ok = true; }
                }
                Registry.make().ok
                """;
        assertTrue(bool(source));
    }

    // typeof a class is "function"
    @Test
    public void test_typeof_class_is_function() {
        assertEquals("function", str("class A {} typeof A"));
    }

    // Async-generator class methods build an async generator (no longer rejected)
    @Test
    public void test_async_generator_method_supported() {
        assertEquals("object", ((JsString) Interpreter.run("typeof (new (class { async *m() {} })()).m()")).getValue());
    }

    // A class-defined [Symbol.iterator] method makes instances iterable in for-of
    @Test
    public void test_class_symbol_iterator() {
        final var source = """
                class Range {
                    constructor(n) { this.n = n; }
                    [Symbol.iterator]() {
                        let i = 0;
                        let n = this.n;
                        return { next() { return i < n ? {value: i++, done: false} : {value: 0, done: true}; } };
                    }
                }
                let s = 0;
                for (const x of new Range(4)) s += x;
                s
                """;
        assertEquals(6, num(source));
    }

    // A class-defined [Symbol.dispose] method runs at using-scope exit
    @Test
    public void test_class_symbol_dispose_under_using() {
        final var source = """
                let disposed = false;
                class Resource {
                    [Symbol.dispose]() { disposed = true; }
                }
                { using r = new Resource(); }
                disposed
                """;
        assertTrue(bool(source));
    }

    // A static [Symbol.hasInstance] method overrides the default instanceof behavior
    @Test
    public void test_symbol_has_instance_override() {
        assertTrue(bool("class C { static [Symbol.hasInstance](x) { return true; } } ({}) instanceof C"));
        assertFalse(bool("class C { static [Symbol.hasInstance](x) { return false; } } new C() instanceof C"));
    }

    // The value being tested is passed as the argument to [Symbol.hasInstance]
    @Test
    public void test_symbol_has_instance_receives_left() {
        final var even = "class Even { static [Symbol.hasInstance](n) { return n % 2 === 0; } }";
        assertTrue(bool(even + " 4 instanceof Even"));
        assertFalse(bool(even + " 3 instanceof Even"));
    }

    // new.target reports the constructor a call was invoked with, and undefined for a plain call
    @Test
    public void test_new_target() {
        assertTrue(bool("class E { constructor() { this.t = new.target === E; } } new E().t"));
        assertEquals("undefined", str("function f() { return typeof new.target; } f()"));
        assertTrue(bool("function f() { this.r = new.target === f; } new f().r"));
        assertTrue(bool("function f() { const g = () => new.target; this.r = g() === f; } new f().r"));
        assertEquals("B",
                str("class A { constructor() { this.t = new.target.name; } } class B extends A {} new B().t"));
        assertEquals("undefined", str("typeof new.target"));
    }

    // new.target requires the `target` property name
    @Test
    public void test_new_target_rejects_other_names() {
        assertThrows(RuntimeException.class, () -> Interpreter.run("new.other"));
    }

    // A computed method key that coerces to a non-symbol string installs under that string name
    @Test
    public void test_computed_method_key_coerces_to_string() {
        assertEquals(4, num("class C { [1 + 1]() { return 4; } } new C()['2']()"));
    }

    // An instance field declared with no initializer defaults to undefined
    @Test
    public void test_instance_field_without_initializer() {
        assertEquals("undefined", str("class A { x; } typeof (new A()).x"));
    }

    // A computed instance field key that coerces to a non-symbol string sets that named property
    @Test
    public void test_instance_field_computed_string_key() {
        assertEquals(5, num("class A { ['x' + 'y'] = 5; } new A().xy"));
    }

    // `class x extends x {}` evaluates ClassHeritage in the class's own scope, where the class name
    // is bound but not yet initialised (TDZ), so referencing it in the heritage throws ReferenceError
    // rather than resolving to an outer binding of the same name
    @Test
    public void test_class_name_in_own_heritage_is_a_tdz_reference_error() {
        assertThrows(ReferenceErrorException.class, () -> Interpreter.run("var x = (class x extends x {});"));
    }

    // A computed field name is never subject to the literal-PropName early error, even when it
    // evaluates to "constructor" at run time - CreateDataPropertyOrThrow just installs it as an
    // ordinary own data property on the instance
    @Test
    public void test_computed_field_named_constructor_is_allowed() {
        final var source = """
                var x = 'constructor';
                class C { [x]; }
                var c = new C();
                JSON.stringify([c.hasOwnProperty('constructor'), C.hasOwnProperty('constructor')])
                """;
        assertEquals("[true,false]", str(source));
    }

    // Public field initialization is a real CreateDataPropertyOrThrow: it fires a Proxy's own
    // defineProperty trap and rejects a field on a non-extensible receiver instead of silently
    // dropping it
    @Test
    public void test_public_field_init_goes_through_proxy_definetrap() {
        final var source = """
                function ProxyBase() {
                    return new Proxy(this, { defineProperty(t, k, d) { throw new TypeError('trapped'); } });
                }
                class Base extends ProxyBase { f = 'x'; }
                var threw = false;
                try { new Base(); } catch (e) { threw = e instanceof TypeError; }
                threw
                """;
        assertTrue(bool(source));
    }

    // A field on an already non-extensible instance is rejected, matching CreateDataPropertyOrThrow's
    // failure on a non-extensible receiver
    @Test
    public void test_public_field_init_rejected_on_frozen_instance() {
        final var source = """
                class Base { constructor() { Object.preventExtensions(this); } }
                class C extends Base { f = 1; }
                var threw = false;
                try { new C(); } catch (e) { threw = e instanceof TypeError; }
                threw
                """;
        assertTrue(bool(source));
    }

    // A class field/method literally named with a BigInt property name uses the exact decimal string
    // form of the BigInt's numeric value, both in object literals and class bodies
    @Test
    public void test_bigint_literal_property_and_method_names() {
        final var source = """
                var o = { 1n() { return 'bar'; } };
                class C { 1n() { return 'baz'; } }
                JSON.stringify([o['1'](), new C()['1']()])
                """;
        assertEquals("[\"bar\",\"baz\"]", str(source));
    }

    // ASI splits a field literally named "get"/"set" from a following generator method when a
    // newline separates them, since `*` can never continue the accessor-modifier production
    @Test
    public void test_field_named_get_followed_by_generator_is_two_members() {
        final var source = """
                class A {
                    get
                    *a() {}
                }
                var a = new A();
                JSON.stringify([A.prototype.hasOwnProperty('a'), a.hasOwnProperty('get')])
                """;
        assertEquals("[true,true]", str(source));
    }
}
