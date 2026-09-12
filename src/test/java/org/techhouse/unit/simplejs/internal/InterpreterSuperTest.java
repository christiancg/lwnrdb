package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

public class InterpreterSuperTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

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

    @Test
    public void test_super_static_method() {
        final var source = """
                class Base { static greet() { return 'hi'; } }
                class Sub extends Base { static greet() { return super.greet() + '!'; } }
                Sub.greet()
                """;
        assertEquals("hi!", str(source));
    }

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

    @Test
    public void test_super_property_read_on_null_proto_home_throws() {
        final var dotRead = """
                var obj = { method() { return super.x; } };
                Object.setPrototypeOf(obj, null);
                obj.method()
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(dotRead));
        final var bracketRead = """
                var obj = { method() { return super['x']; } };
                Object.setPrototypeOf(obj, null);
                obj.method()
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(bracketRead));
    }

    @Test
    public void test_super_call_spread_copies_symbol_keyed_accessor() {
        final var source = """
                var s = Symbol('foo');
                var o = {};
                var called = false;
                Object.defineProperty(o, s, { get: function() { called = true; return 'bar'; }, enumerable: true });
                class Base { constructor(obj) { this.gotSymbol = obj[s] === 'bar'; } }
                class Sub extends Base { constructor() { super({...o}); } }
                var result = new Sub();
                (result.gotSymbol && called)
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_bare_super() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("super"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("super.foo"));
    }

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

    @Test
    public void test_super_static_getter_call() {
        final var source = """
                class Base { static get make() { return function() { return 5; }; } }
                class Sub extends Base { static test() { return super.make(); } }
                Sub.test()
                """;
        assertEquals(5, num(source));
    }

    @Test
    public void test_super_static_call_not_found() {
        final var source = """
                class Base {}
                class Sub extends Base { static test() { return super.missing(); } }
                Sub.test()
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_super_instance_call_not_callable() {
        final var source = """
                class Base { get prop() { return 5; } }
                class Sub extends Base { call() { return super.prop(); } }
                new Sub().call()
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_super_static_getter_read() {
        final var source = """
                class Base { static get val() { return 7; } }
                class Sub extends Base { static read() { return super.val; } }
                Sub.read()
                """;
        assertEquals(7, num(source));
    }

    @Test
    public void test_super_static_plain_field_read() {
        final var source = """
                class Base { static x = 42; }
                class Sub extends Base { static read() { return super.x; } }
                Sub.read()
                """;
        assertEquals(42, num(source));
    }

    @Test
    public void test_super_instance_property_read_missing() {
        final var source = """
                class Base {}
                class Sub extends Base { call() { return super.missingProp; } }
                typeof new Sub().call()
                """;
        assertEquals("undefined", str(source));
    }

    @Test
    public void test_this_before_super_is_a_reference_error() {
        assertThrows(ReferenceErrorException.class, () -> Interpreter
                .run("class B {} class C extends B { constructor() { this.x = 1; super(); } } new C()"));
        assertThrows(ReferenceErrorException.class,
                () -> Interpreter.run("class B {} class C extends B { constructor() { () => this; this; } } new C()"));
    }

    @Test
    public void test_missing_or_repeated_super_call_is_a_reference_error() {
        assertThrows(ReferenceErrorException.class,
                () -> Interpreter.run("class B {} class C extends B { constructor() {} } new C()"));
        assertThrows(ReferenceErrorException.class,
                () -> Interpreter.run("class B {} class C extends B { constructor() { super(); super(); } } new C()"));
    }

    @Test
    public void test_super_call_from_arrow_initializes_this() {
        assertEquals(3,
                num("class B { constructor() { this.b = 1; } } "
                        + "class C extends B { constructor() { (() => super())(); this.c = 2; } } "
                        + "const c = new C(); c.b + c.c"));
    }

    @Test
    public void test_private_method_is_not_installed_before_super_returns() {
        final var source = """
                class C { constructor() { this.f(); } }
                class D extends C { f() { return this.#m(); } #m() { return 42; } }
                new D()
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
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

    // super.x = v writes with the home object's [[Prototype]] as the [[Set]] target and `this` as the
    // receiver, so a setter doing `super.x = v` does not re-enter its own accessor and recurse forever.
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

    @Test
    public void test_delete_super_computed_checks_this_before_evaluating_key() {
        final var source = """
                class Base {
                    constructor() { throw new Error('base constructor called'); }
                }
                class Derived extends Base {
                    constructor() { delete super[(super(), 0)]; }
                }
                new Derived();
                """;
        assertThrows(ReferenceErrorException.class, () -> Interpreter.run(source));
    }

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

    @Test
    public void test_super_call_binds_and_returns_base_functions_override_object() {
        // The explicit `return this;` sidesteps an unrelated, already-tracked gap; this test is only about
        // super()'s own return value.
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

    @Test
    public void test_symbol_subclass_super_call_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new (class extends Symbol {})()"));
        final var source = """
                class S extends Symbol { constructor() { super(); } }
                new S()
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

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
