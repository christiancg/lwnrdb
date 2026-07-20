package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.exceptions.UnsupportedNodeException;
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

    // extends + super(...) chains constructors and orders base/derived fields
    @Test
    public void test_extends_and_super_constructor() {
        final var source = """
                class Animal {
                    constructor(name) { this.name = name; }
                }
                class Dog extends Animal {
                    constructor(name) { super(name); this.legs = 4; }
                }
                let d = new Dog('Rex');
                d.name + ':' + d.legs
                """;
        assertEquals("Rex:4", str(source));
    }

    // super.method() dispatches to the parent method with the correct this
    @Test
    public void test_super_method_call() {
        final var source = """
                class Shape {
                    area() { return 0; }
                    describe() { return 'area=' + this.area(); }
                }
                class Square extends Shape {
                    constructor(s) { super(); this.s = s; }
                    area() { return this.s * this.s; }
                    baseArea() { return super.area(); }
                }
                let sq = new Square(3);
                sq.area() + ',' + sq.baseArea()
                """;
        assertEquals("9,0", str(source));
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

    // instanceof walks the class heritage chain
    @Test
    public void test_instanceof() {
        assertTrue(bool("class A {} new A() instanceof A"));
        assertTrue(bool("class A {} class B extends A {} new B() instanceof A"));
        assertFalse(bool("class A {} class B {} new B() instanceof A"));
        assertFalse(bool("class A {} 5 instanceof A"));
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

    // super.method() dispatches to a parent static method
    @Test
    public void test_super_static_method() {
        final var source = """
                class Base { static greet() { return 'hi'; } }
                class Sub extends Base { static greet() { return super.greet() + '!'; } }
                Sub.greet()
                """;
        assertEquals("hi!", str(source));
    }

    // super.prop reads a parent getter and a parent method reference
    @Test
    public void test_super_property_read() {
        assertEquals("base", str("""
                class Base { get label() { return 'base'; } }
                class Sub extends Base { getLabel() { return super.label; } }
                new Sub().getLabel()
                """));
        assertEquals(1, num("""
                class Base { static val() { return 1; } }
                class Sub extends Base {
                    static val() { return 2; }
                    static viaRead() { let f = super.val; return f(); }
                }
                Sub.viaRead()
                """));
    }

    // Extending a non-constructor throws a TypeError
    @Test
    public void test_extends_non_constructor() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("class A extends 5 {}"));
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

    // instanceof with a non-callable right-hand side throws a TypeError
    @Test
    public void test_instanceof_non_callable_rhs() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("5 instanceof 5"));
    }

    // Async/generator class methods are not yet supported
    @Test
    public void test_async_method_unsupported() {
        assertThrows(UnsupportedNodeException.class, () -> Interpreter.run("class A { async m() {} } new A().m()"));
    }

    // A bare super expression is a syntax error
    @Test
    public void test_bare_super() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("super"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("super.foo"));
    }
}
