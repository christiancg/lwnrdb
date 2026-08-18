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

    // a derived class's private methods are installed only once super() returns
    @Test
    public void test_private_method_is_not_installed_before_super_returns() {
        final var source = """
                class C { constructor() { this.f(); } }
                class D extends C { f() { return this.#m(); } #m() { return 42; } }
                new D()
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

    // A class object has real own name/length/prototype properties, in that declaration order
    @Test
    public void test_class_has_own_name_length_and_prototype_properties() {
        assertTrue(bool("class C {} Object.getOwnPropertyNames(C).includes('name')"));
        assertTrue(bool("class C {} Object.getOwnPropertyNames(C).includes('length')"));
        assertTrue(bool("class C {} Object.getOwnPropertyNames(C).includes('prototype')"));
        assertEquals("C", str("class C {} C.name"));
        assertEquals(0, num("class C {} C.length"));
        assertEquals(2, num("class C { constructor(a, b) {} } C.length"));
        assertEquals(1, num("class C { constructor(a, b = 1) {} } C.length"));
        assertEquals("Anon", str("const Anon = class {}; Anon.name"));
        // name/length are non-writable but configurable; prototype is fully non-configurable
        assertTrue(bool("class C {} C.name = 'x'; C.name === 'C'"));
        assertTrue(bool("class C {} C.length = 9; C.length === 0"));
        assertFalse(bool("class C {} Object.getOwnPropertyDescriptor(C, 'prototype').configurable"));
    }

    // An explicit static "name" member (method, accessor or field) takes precedence over the
    // inferred class-expression name - the anonymous-class NamedEvaluation must not clobber it
    @Test
    public void test_explicit_static_name_member_beats_inferred_name() {
        assertEquals("function", str("const X = class { static name() {} }; typeof X.name"));
        assertEquals("string", str("const X = class { static name = 'explicit'; }; typeof X.name"));
        assertEquals("explicit", str("const X = class { static name = 'explicit'; }; X.name"));
    }

    // Object.getPrototypeOf(C) chains to the heritage (a class, a plain/native constructor, or
    // %Function.prototype% by default); Object.getPrototypeOf(C.prototype) chains separately
    @Test
    public void test_class_object_and_prototype_chain_to_the_right_parent() {
        assertTrue(bool("class C {} Object.getPrototypeOf(C) === Function.prototype"));
        assertTrue(bool("class C {} Object.getPrototypeOf(C.prototype) === Object.prototype"));
        assertTrue(bool("class A {} class B extends A {} Object.getPrototypeOf(B) === A"));
        assertTrue(bool("class A {} class B extends A {} Object.getPrototypeOf(B.prototype) === A.prototype"));
        assertTrue(bool("class C extends null {} Object.getPrototypeOf(C) === Function.prototype"));
    }

    // A native-heritage class's instances are real JsObjects (klass + proto linked to the class's
    // own prototype), so instanceof must not fall back to the shared native intrinsic prototype -
    // that would make every plain `new Set()` look like an instance of any `class extends Set`
    @Test
    public void test_instanceof_with_native_heritage_does_not_match_every_native_instance() {
        final var setup = "class MySet extends Set {} ";
        assertFalse(bool(setup + "new Set() instanceof MySet"));
        assertTrue(bool(setup + "new MySet() instanceof MySet"));
        assertTrue(bool(setup + "new MySet() instanceof Set"));
        assertTrue(bool(setup + "Object.getPrototypeOf(new MySet()) === MySet.prototype"));
        assertFalse(bool(setup + "Object.getPrototypeOf(new MySet()) === Set.prototype"));
    }

    // A derived constructor returning an object short-circuits [[Construct]] before `this` is ever
    // consulted (spec step 13a), so it never needs to have called super() at all
    @Test
    public void test_derived_constructor_returning_object_need_not_call_super() {
        assertEquals("object", str("class C extends null { constructor() { return {}; } } typeof new C()"));
        final var source = """
                var obj;
                class Foo extends null {
                    constructor() { return obj = {}; }
                }
                var f = new Foo();
                f === obj && Object.getPrototypeOf(f) === Object.prototype
                """;
        assertTrue(bool(source));
    }

    // super.x = v on the proto chain writes through the home object's own [[Prototype]] as the
    // [[Set]] target, receiver `this` - not `this` as the target - so a setter that itself does
    // `super.x = v` does not re-enter its own accessor and recurse forever
    @Test
    public void test_object_literal_setter_super_write_does_not_recurse() {
        final var source = """
                var proto = { _x: 0, set x(v) { this._x = v; } };
                var object = { set x(v) { super.x = v; } };
                Object.setPrototypeOf(object, proto);
                var result = (object.x = 1);
                JSON.stringify([result, object._x, Object.getPrototypeOf(object)._x])
                """;
        assertEquals("[1,1,0]", str(source));
    }

    // GetSuperBase() is captured before a computed super-member key is coerced/evaluated, so a
    // toString side effect that mutates the home object's prototype must not change which object the
    // read/write actually lands on
    @Test
    public void test_super_computed_key_evaluated_after_getsuperbase_captured() {
        final var source = """
                var proto = { p: 'ok' };
                var proto2 = { p: 'bad' };
                var obj = {
                    __proto__: proto,
                    m() { return super[key]; }
                };
                var key = { toString() { Object.setPrototypeOf(obj, proto2); return 'p'; } };
                obj.m()
                """;
        assertEquals("ok", str(source));
    }

    // super() in a plain-function heritage's derived constructor evaluates to BindThisValue's result
    // (the constructed `this`), and a base function that returns a custom object overrides `this`
    // with that object rather than the pre-allocated instance
    @Test
    public void test_super_call_binds_and_returns_base_functions_override_object() {
        // An explicit `return this;` sidesteps the unrelated (already-tracked) gap where a derived
        // constructor falling off the end without one does not yet re-read the environment's
        // (possibly-replaced) `this` binding - this test is only about super()'s own return value.
        final var source = """
                var customThisValue = {};
                var boundThisValue;
                function Parent() { return customThisValue; }
                class Child extends Parent {
                    constructor() { boundThisValue = super(); return this; }
                }
                var c = new Child();
                boundThisValue === customThisValue && c === customThisValue
                """;
        assertTrue(bool(source));
    }

    // GetSuperConstructor() reads the active constructor's own (dynamic) [[Prototype]], so mutating a
    // class's own prototype after definition changes what super() resolves to - IsConstructor is
    // checked after ArgumentListEvaluation, so a side effect in the argument list is still observed
    @Test
    public void test_super_call_checks_dynamic_prototype_after_evaluating_arguments() {
        final var source = """
                var evaluatedArg = false;
                var caught;
                class C extends Object {
                    constructor() {
                        try { super(evaluatedArg = true); } catch (err) { caught = err; }
                    }
                }
                Object.setPrototypeOf(C, parseInt);
                try { new C(); } catch (_) {}
                JSON.stringify([typeof caught, caught instanceof TypeError, evaluatedArg])
                """;
        assertEquals("[\"object\",true,true]", str(source));
    }

    // A repeated super() call's own side effects (argument evaluation, the base constructor running
    // again) are observable before BindThisValue's "already initialised" check finally throws
    @Test
    public void test_repeated_super_call_runs_side_effects_before_throwing() {
        final var source = """
                var baseCalled = 0;
                class Base { constructor() { baseCalled++; } }
                var fCalled = 0;
                function f() { fCalled++; return 3; }
                var exn = null;
                class Sub extends Base {
                    constructor() {
                        super();
                        baseCalled = 0;
                        fCalled = 0;
                        try { super(f()); } catch (e) { exn = e; }
                    }
                }
                new Sub();
                JSON.stringify([exn instanceof ReferenceError, fCalled, baseCalled])
                """;
        assertEquals("[true,1,1]", str(source));
    }

    // `this` accessed before super() has run is a TDZ ReferenceError, even nested inside super()'s own
    // argument list (`super(super())`), where the inner call's own bookkeeping must not silently let
    // the outer call succeed without ever throwing at all
    @Test
    public void test_nested_super_call_in_argument_list_throws_reference_error() {
        final var source = """
                class Base {}
                class C extends Base {
                    constructor() { super(super()); }
                }
                var threw = false;
                try { new C(); } catch (e) { threw = e instanceof ReferenceError; }
                threw
                """;
        assertTrue(bool(source));
    }

    // A static method's `super.x` resolves through the class's own dynamic [[Prototype]], which
    // holds even when the heritage is a plain (non-class) constructor, not just another class
    @Test
    public void test_static_super_property_reads_through_plain_function_heritage() {
        final var source = """
                function Parent() {}
                Parent.test262 = 'test262';
                var value;
                class C extends Parent {
                    static { value = super.test262; }
                }
                value
                """;
        assertEquals("test262", str(source));
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

    // A concise method/getter/setter (kind "method") never gets an own "prototype", unlike a normal
    // class constructor or a normal function
    @Test
    public void test_prototype_absent_on_methods_getters_setters() {
        final var source = """
                class C {
                    method() {}
                    get accessor() { return 1; }
                    set accessor(v) {}
                }
                var methodDesc = Object.getOwnPropertyDescriptor(C.prototype, 'method');
                var accessorDesc = Object.getOwnPropertyDescriptor(C.prototype, 'accessor');
                JSON.stringify([
                    'prototype' in methodDesc.value,
                    'prototype' in accessorDesc.get,
                    'prototype' in accessorDesc.set,
                    'prototype' in C
                ])
                """;
        assertEquals("[false,false,false,true]", str(source));
    }

    // A base constructor that overrides `this` by explicitly returning an object is what a derived
    // constructor's implicit (no explicit return) completion must resolve to - not the freshly
    // allocated instance discarded by that override
    @Test
    public void test_derived_constructor_implicit_return_resolves_base_override() {
        final var source = """
                class Base { constructor(obj) { return obj; } }
                class C extends Base {
                    #val;
                    constructor(obj) { super(obj); this.#val = 42; }
                    static val(obj) { return obj.#val; }
                }
                var t = new C({});
                C.val(t)
                """;
        assertEquals(42, num(source));
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

    // The Symbol constructor must reject being reached via `new`, including a subclass's super()
    // call (which never invokes the plain-call path)
    @Test
    public void test_symbol_subclass_super_call_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new (class extends Symbol {})()"));
        final var source = """
                class S extends Symbol { constructor() { super(); } }
                new S()
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    // A computed super-member key that evaluates to a Symbol dispatches through the symbol table
    // instead of being stringified (which would throw for a Symbol)
    @Test
    public void test_super_computed_symbol_key_dispatches_to_symbol_method() {
        final var source = """
                class RE extends RegExp {
                    [Symbol.replace](str, replacement) {
                        return super[Symbol.replace](str, replacement);
                    }
                }
                new RE('a', 'g')[Symbol.replace]('banana', 'o')
                """;
        assertEquals("bonono", str(source));
    }
}
