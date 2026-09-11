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

    // GetSuperBase() is the home object's own [[Prototype]]: when that has been explicitly nulled
    // (Object.setPrototypeOf(obj, null)), RequireObjectCoercible must throw a TypeError rather than
    // falling back to Object.prototype (which would happen if "never set" and "deliberately null"
    // proto were conflated) or silently answering undefined.
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

    // A spread argument to super(...) must CopyDataProperties in [[OwnPropertyKeys]] order,
    // including Symbol-keyed accessor properties - a Symbol key's getter has to actually run, not
    // just be skipped because JsObject cannot invoke its own accessors without the ops seam.
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

    // A bare super expression is a syntax error
    @Test
    public void test_bare_super() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("super"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("super.foo"));
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

    // `delete super[expr]` resolves GetThisBinding (the base of the super reference) before
    // evaluating the computed key expression: in a derived constructor whose `this` is still
    // uninitialised, that resolution throws immediately, so a `super()` call nested inside the key
    // expression never gets a chance to run (and so never initialises `this`).
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
