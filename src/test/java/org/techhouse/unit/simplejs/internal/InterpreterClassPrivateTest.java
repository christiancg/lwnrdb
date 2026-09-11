package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class InterpreterClassPrivateTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // Private fields are read and written only from within the class body
    @Test
    public void test_private_field() {
        final var source = """
                class Counter {
                    #count = 0;
                    inc() { this.#count++; return this.#count; }
                }
                let c = new Counter();
                c.inc();
                c.inc()
                """;
        assertEquals(2, num(source));
    }

    // Private methods are callable from other members
    @Test
    public void test_private_method() {
        final var source = """
                class A {
                    #secret() { return 99; }
                    reveal() { return this.#secret(); }
                }
                new A().reveal()
                """;
        assertEquals(99, num(source));
    }

    // The #x in obj brand check reports private-field presence
    @Test
    public void test_private_brand_check() {
        final var source = """
                class A {
                    #x = 1;
                    static has(obj) { return #x in obj; }
                }
                A.has(new A()) + ',' + A.has({})
                """;
        assertEquals("true,false", str(source));
    }

    // The brand check also recognises private methods by class membership
    @Test
    public void test_private_method_brand_check() {
        final var source = """
                class A {
                    #m() { return 1; }
                    static has(obj) { return #m in obj; }
                }
                A.has(new A()) + ',' + A.has({})
                """;
        assertEquals("true,false", str(source));
    }

    // Private getters and setters are usable from within the class
    @Test
    public void test_private_accessors() {
        final var source = """
                class P {
                    #v = 5;
                    get #doubled() { return this.#v * 2; }
                    set #doubled(x) { this.#v = x; }
                    run() { this.#doubled = 21; return this.#doubled; }
                }
                new P().run()
                """;
        assertEquals(42, num(source));
    }

    // Private members support compound and logical assignment
    @Test
    public void test_private_compound_assignment() {
        assertEquals(8, num("class A { #n = 5; run() { this.#n += 3; return this.#n; } } new A().run()"));
        assertEquals(7, num("class A { #n = 0; run() { this.#n ||= 7; return this.#n; } } new A().run()"));
    }

    // Reading a private member off a foreign object throws a TypeError
    @Test
    public void test_private_brand_miss() {
        final var source = """
                class A {
                    #x = 1;
                    static read(obj) { return obj.#x; }
                }
                A.read({})
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }
    // a private method is reachable only through an object branded as an instance of its class
    @Test
    public void test_private_method_requires_the_declaring_class_brand() {
        assertEquals(1, num("class C { #m() { return 1; } run() { return this.#m(); } } new C().run()"));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("class C { #m() { return 1; } run() { return this.#m(); } } " + "new C().run.call({})"));
    }

    // a private accessor is brand-checked on both the read and the write side
    @Test
    public void test_private_accessor_requires_the_declaring_class_brand() {
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("class C { get #g() { return 1; } run() { return this.#g; } } " + "new C().run.call({})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("class C { set #s(v) {} run() { this.#s = 1; } } new C().run.call({})"));
    }

    // an inner class's private name is not reachable through an instance of the outer class
    @Test
    public void test_nested_class_private_name_does_not_leak_to_the_outer_instance() {
        final var source = """
                class C {
                  get #m() { return 'outer'; }
                  B = class {
                    get #m() { return 'inner'; }
                    read(o) { return o.#m; }
                  };
                }
                const c = new C();
                const b = new c.B();
                b.read(c)
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    // writing to a private method or a getter-only accessor is a TypeError, not a silent field add
    @Test
    public void test_private_method_and_getter_only_accessor_are_not_writable() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("class C { #m() {} run() { this.#m = 1; } } new C().run()"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("class C { get #g() { return 1; } run() { this.#g = 1; } } new C().run()"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("class C { static #m() {} static run() { C.#m = 1; } } C.run()"));
    }

    // `#x in obj` reports the brand, so it stays false for a foreign object
    @Test
    public void test_private_brand_check_operator_follows_the_brand() {
        assertTrue(bool("class C { #m() {} static has(o) { return #m in o; } } C.has(new C())"));
        assertFalse(bool("class C { #m() {} static has(o) { return #m in o; } } C.has({})"));
    }

    // A private field's assignment target can appear inside a destructuring pattern (object/array
    // pattern, or a for-of head), which routes through the same private-member write as `this.#f = v`
    @Test
    public void test_private_field_as_destructuring_assignment_target() {
        final var source = """
                class C {
                    #field;
                    m(obj) { ({ a: this.#field } = obj); return this.#field; }
                    n() { for (this.#field of [9]) ; return this.#field; }
                }
                var c = new C();
                JSON.stringify([c.m({ a: 5 }), c.n()])
                """;
        assertEquals("[5,9]", str(source));
    }

    // Private field/getter/method access reaches through a Proxy wrapping the real instance (the
    // base constructor returned the Proxy, so private storage lives on its target)
    @Test
    public void test_private_member_access_through_proxy() {
        final var fieldSource = """
                class Base { constructor() { return new Proxy(this, { get: (o, p) => o[p] }); } }
                class C extends Base { #f = 3; method() { return this.#f; } }
                new C().method()
                """;
        assertEquals(3, num(fieldSource));

        final var getterSource = """
                class Base { constructor() { return new Proxy(this, { get: (o, p) => o[p] }); } }
                class C extends Base { get #f() { return 5; } method() { return this.#f; } }
                new C().method()
                """;
        assertEquals(5, num(getterSource));

        final var methodSource = """
                class Base { constructor() { return new Proxy(this, { get: (o, p) => o[p] }); } }
                class C extends Base { #f() { return 7; } method() { return this.#f(); } }
                new C().method()
                """;
        assertEquals(7, num(methodSource));
    }
}
