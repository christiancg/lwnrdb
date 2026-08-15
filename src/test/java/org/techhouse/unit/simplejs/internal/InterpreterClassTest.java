package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
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

    // Async-generator class methods build an async generator (no longer rejected)
    @Test
    public void test_async_generator_method_supported() {
        assertEquals("object", ((JsString) Interpreter.run("typeof (new (class { async *m() {} })()).m()")).getValue());
    }

    // A bare super expression is a syntax error
    @Test
    public void test_bare_super() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("super"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("super.foo"));
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

    // Without a hasInstance override, ordinary heritage-based instanceof still applies
    @Test
    public void test_instanceof_without_override() {
        assertTrue(bool("class A {} class B extends A {} new B() instanceof A"));
        assertFalse(bool("class A {} class B {} new B() instanceof A"));
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

    // Another class cannot reach a static private member
    @Test
    public void test_static_private_is_not_shared() {
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("class A { static #x = 1 } class B { static probe() { return A.#x } } B.probe()"));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("class A { static #m() {} } class B { static probe() { return A.#m() } } B.probe()"));
    }

    // A static block can use static private state
    @Test
    public void test_static_block_with_static_private() {
        assertEquals(4, num("class A { static #x = 2; static out; static { A.out = A.#x * 2 } } A.out"));
    }

    // A class exposes a real prototype object that instances are linked to
    @Test
    public void test_class_prototype_is_object() {
        assertEquals("object", str("class E { m() {} } typeof E.prototype"));
        assertTrue(bool("class E {} E.prototype.constructor === E"));
        assertTrue(bool("class E {} Object.getPrototypeOf(new E()) === E.prototype"));
        assertTrue(bool("class E {} typeof Object.getPrototypeOf(new E()) === 'object'"));
    }

    // Patching, deleting and adding prototype members is visible on existing instances
    @Test
    public void test_prototype_is_patchable() {
        assertEquals(2, num("class E { m() { return 1; } } E.prototype.m = function() { return 2; }; new E().m()"));
        assertEquals("undefined", str("class E { m() { return 1; } } delete E.prototype.m; typeof new E().m"));
        assertEquals(7, num("class E {} const e = new E(); E.prototype.extra = function() { return 7; }; e.extra()"));
    }

    // Prototype members are non-enumerable, so instances still serialise as their own state
    @Test
    public void test_prototype_entries_not_enumerable() {
        assertEquals(0, num("class E { m() {} } Object.keys(E.prototype).length"));
        assertEquals("{}", str("class E { m() {} } JSON.stringify(new E())"));
        assertEquals("{\"a\":1}", str("class E { constructor() { this.a = 1; } m() {} } JSON.stringify(new E())"));
    }

    // The prototype chain mirrors the class heritage
    @Test
    public void test_prototype_heritage_chain() {
        final var setup = "class A { m() { return 'a'; } } class B extends A {} ";
        assertTrue(bool(setup + "Object.getPrototypeOf(B.prototype) === A.prototype"));
        assertEquals("a", str(setup + "new B().m()"));
        assertTrue(bool(setup + "new B() instanceof A"));
        assertEquals("ab", str(
                "class A { m() { return 'a'; } } class B extends A { m() { return super.m() + 'b'; } } new B().m()"));
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

    // An abrupt completion (break) inside a static block stops processing later static members
    @Test
    public void test_static_block_break_stops_early() {
        final var source = """
                class A {
                    static out = 1;
                    static {
                        A.out = 2;
                        break;
                        A.out = 99;
                    }
                }
                A.out
                """;
        assertEquals(2, num(source));
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

    // Calling super() where `this` is not an object instance (e.g. a static method) is rejected
    @Test
    public void test_super_call_outside_constructor() {
        final var source = """
                class Base {}
                class Sub extends Base {
                    static test() { super(); }
                }
                Sub.test()
                """;
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run(source));
    }

    // super.method() dispatches through a parent static getter that returns a callable
    @Test
    public void test_super_static_getter_call() {
        final var source = """
                class Base { static get make() { return function() { return 5; }; } }
                class Sub extends Base { static test() { return super.make(); } }
                Sub.test()
                """;
        assertEquals(5, num(source));
    }

    // super.method() with neither a method nor a getter on the parent static side throws
    @Test
    public void test_super_static_call_not_found() {
        final var source = """
                class Base {}
                class Sub extends Base { static test() { return super.missing(); } }
                Sub.test()
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    // super.method() on the instance side where the resolved value is not callable throws
    @Test
    public void test_super_instance_call_not_callable() {
        final var source = """
                class Base { get prop() { return 5; } }
                class Sub extends Base { call() { return super.prop(); } }
                new Sub().call()
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    // super.prop reads a parent static getter (not a call)
    @Test
    public void test_super_static_getter_read() {
        final var source = """
                class Base { static get val() { return 7; } }
                class Sub extends Base { static read() { return super.val; } }
                Sub.read()
                """;
        assertEquals(7, num(source));
    }

    // super.prop reads a plain parent static field (no getter, no method)
    @Test
    public void test_super_static_plain_field_read() {
        final var source = """
                class Base { static x = 42; }
                class Sub extends Base { static read() { return super.x; } }
                Sub.read()
                """;
        assertEquals(42, num(source));
    }

    // super.prop on the instance side falls through to undefined when nothing matches
    @Test
    public void test_super_instance_property_read_missing() {
        final var source = """
                class Base {}
                class Sub extends Base { call() { return super.missingProp; } }
                typeof new Sub().call()
                """;
        assertEquals("undefined", str(source));
    }

    // instanceof against a bound function delegates to the bound target
    @Test
    public void test_instanceof_bound_function() {
        assertTrue(bool("function F() {} const B = F.bind(null); new F() instanceof B"));
    }

    // instanceof against a native function with no prototype is false
    @Test
    public void test_instanceof_native_function_without_prototype() {
        assertFalse(bool("5 instanceof parseInt"));
    }

    // instanceof against an unrelated native constructor's prototype is false
    @Test
    public void test_instanceof_native_prototype_mismatch() {
        assertFalse(bool("[] instanceof Map"));
    }

    // a derived constructor's `this` is unreachable until super() returns
    @Test
    public void test_this_before_super_is_a_reference_error() {
        assertThrows(ReferenceErrorException.class, () -> Interpreter
                .run("class B {} class C extends B { constructor() { this.x = 1; super(); } } new C()"));
        assertThrows(ReferenceErrorException.class,
                () -> Interpreter.run("class B {} class C extends B { constructor() { () => this; this; } } new C()"));
    }

    // a derived constructor that never calls super(), or calls it twice, is a reference error
    @Test
    public void test_missing_or_repeated_super_call_is_a_reference_error() {
        assertThrows(ReferenceErrorException.class,
                () -> Interpreter.run("class B {} class C extends B { constructor() {} } new C()"));
        assertThrows(ReferenceErrorException.class,
                () -> Interpreter.run("class B {} class C extends B { constructor() { super(); super(); } } new C()"));
    }

    // super() from an arrow inside the constructor still initializes `this`
    @Test
    public void test_super_call_from_arrow_initializes_this() {
        assertEquals(3,
                num("class B { constructor() { this.b = 1; } } "
                        + "class C extends B { constructor() { (() => super())(); this.c = 2; } } "
                        + "const c = new C(); c.b + c.c"));
    }

    // static members are real own properties of the class object
    @Test
    public void test_class_statics_are_own_properties() {
        assertTrue(bool("class C { static m() {} } Object.prototype.hasOwnProperty.call(C, 'm')"));
        assertEquals(1, num("class C { static p = 1; } Object.keys(C).length"));
        assertTrue(bool("class C { static m() {} } Object.getOwnPropertyDescriptor(C, 'm').enumerable === false"));
    }
}
