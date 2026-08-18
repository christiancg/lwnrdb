package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class PrototypeProgramTest {
    private static final EJson EJSON = new EJson();

    private static String run(String source) {
        final var result = new SimpleJs().run(source, SimpleHostBindings.empty());
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        return EJSON.toJson(result.getValue());
    }

    private static String errorName(String source) {
        final var result = new SimpleJs().run(source, SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        return result.getErrorName();
    }

    // Array.prototype is a real object carrying the array methods
    @Test
    public void test_array_prototype_is_visible() {
        assertEquals("[\"object\",\"function\",\"function\"]",
                run("return [typeof Array.prototype, typeof Array.prototype.slice, typeof Object.prototype.toString]"));
    }

    // The generic call idiom works for array-like receivers
    @Test
    public void test_generic_call_on_array_like() {
        assertEquals("[1,2,3]", run("function f() { return Array.prototype.slice.call(arguments) } return f(1,2,3)"));
        assertEquals("\"a-b-c\"", run("return Array.prototype.join.call('abc', '-')"));
    }

    // A method added to an intrinsic prototype is found on every instance
    @Test
    public void test_monkey_patch_added_method() {
        assertEquals("3", run(
                "Array.prototype.last = function () { return this[this.length - 1] };" + " return [1, 2, 3].last()"));
    }

    // Reassigning an intrinsic method overrides the builtin
    @Test
    public void test_monkey_patch_overrides_intrinsic() {
        assertEquals("\"X\"", run("Array.prototype.join = function () { return 'X' }; return [1, 2].join()"));
    }

    // Object.prototype methods are shared and callable with an explicit receiver
    @Test
    public void test_object_prototype_shared() {
        assertEquals("[true,false]", run("return [Object.prototype.hasOwnProperty.call({a: 1}, 'a'),"
                + " Object.prototype.hasOwnProperty.call({}, 'a')]"));
    }

    // Function exists as a global with a prototype, but constructing from source is refused
    @Test
    public void test_function_prototype_and_instanceof() {
        assertEquals("[true,\"object\"]",
                run("return [(function () {}) instanceof Function, typeof Function.prototype]"));
        assertEquals("TypeError", errorName("Function('x')"));
        assertEquals("TypeError", errorName("new Function('x')"));
    }

    // A user class can extend Error and the instance keeps both identities
    @Test
    public void test_extends_error_produces_instance() {
        assertEquals("[true,true,\"boom\",\"Error\",7,\"Error: boom\"]",
                run("class E extends Error { constructor(m) { super(m); this.code = 7 } }" + " const e = new E('boom');"
                        + " return [e instanceof E, e instanceof Error, e.message, e.name, e.code, e.toString()]"));
    }

    // Extending a builtin with internal state keeps its instance methods working
    @Test
    public void test_extends_map_and_array() {
        assertEquals("[1,true,true,1]", run("class M extends Map {} const m = new M(); m.set('a', 1);"
                + " return [m.get('a'), m instanceof Map, m instanceof M, m.size]"));
        assertEquals("[2,true]", run("class L extends Array {} const l = new L(); l.push('x'); l.push('y');"
                + " return [l.length, l instanceof Array]"));
    }

    // A caught runtime error is a real error object
    @Test
    public void test_caught_runtime_error_is_type_error() {
        assertEquals("[true,true,true]",
                run("try { null.x } catch (e) { return [e instanceof TypeError, e instanceof Error,"
                        + " e.toString().indexOf('TypeError: ') === 0] }"));
    }

    // super.m() against a builtin superclass now resolves through the intrinsic prototype chain
    @Test
    public void test_super_method_on_native_super_resolves() {
        assertEquals("true", run("class E extends Error { m() { return super.toString() } }"
                + " return new E('x').m().indexOf('Error') === 0"));
    }

    // Primitive wrapper objects and new Object()
    @Test
    public void test_wrapper_objects() {
        assertEquals("[\"object\",2,1,\"AB\",\"object\",true]",
                run("return [typeof new Object(), new Number(1) + 1, new String('a').length,"
                        + " new String('ab').toUpperCase(), typeof new Boolean(1), new Boolean(1) == true]"));
    }

    // new Object(x) returns an object-like argument unchanged
    @Test
    public void test_new_object_with_object_argument() {
        assertEquals("true", run("const o = {a: 1}; return new Object(o) === o"));
    }

    // Intrinsic entries are non-enumerable, so enumeration and serialisation are unaffected
    @Test
    public void test_intrinsics_are_not_enumerable() {
        assertEquals("[0,\"[1]\",\"{\\\"a\\\":1}\",0]",
                run("return [Object.keys([]).length, JSON.stringify([1]), JSON.stringify({a: 1}),"
                        + " Object.keys({}).length]"));
    }

    // Intrinsic entries are configurable, so deleting one removes the method
    @Test
    public void test_delete_intrinsic_method() {
        assertEquals("[\"function\",\"undefined\"]", run(
                "const before = typeof [].push; delete Array.prototype.push;" + " return [before, typeof [].push]"));
    }

    // Extending a builtin that has no prototype still fails
    @Test
    public void test_extends_value_without_prototype() {
        assertEquals("TypeError", errorName("class X extends Math {}"));
        assertEquals("TypeError", errorName("class X extends 5 {}"));
    }

    // Patching an intrinsic with a non-function then calling it fails
    @Test
    public void test_monkey_patch_with_non_function() {
        assertEquals("TypeError", errorName("Array.prototype.join = 1; [1].join()"));
    }

    // A wrong receiver for a delegating wrapper names the method it was called on. A raw number is
    // no longer "wrong" (ToObject boxes it into an empty array-like), so null - which ToObject
    // rejects - stands in as a receiver that is genuinely still incompatible.
    @Test
    public void test_wrong_receiver_throws_type_error() {
        assertEquals("[true,true]", run("try { Array.prototype.push.call(null, 2) } catch (e) {"
                + " return [e instanceof TypeError, e.message.indexOf('Array.prototype.push') === 0] }"));
    }

    // Function name/length/toString metadata; `g` takes its name from the declarator per
    // NamedEvaluation, even though the function expression itself is anonymous
    @Test
    public void test_function_metadata() {
        assertEquals("[\"f\",2,true,\"g\",0]", run("function f(a, b, c = 1) {} const g = function () {};"
                + " return [f.name, f.length, f.toString().indexOf('function f') === 0, g.name, g.length]"));
    }

    // Rest and pattern parameters stop the length count
    @Test
    public void test_function_length_stops_at_rest() {
        assertEquals("[1,1]", run("function f(a, ...rest) {} function g(a, {b}) {} return [f.length, g.length]"));
    }

    // Error cause, stack and toString
    @Test
    public void test_error_metadata() {
        assertEquals("[\"c\",true,\"Error\",\"TypeError: t\",\"RangeError\"]",
                run("const e = new Error('m', { cause: 'c' });"
                        + " return [e.cause, typeof e.stack === 'string', new Error().toString(),"
                        + " new TypeError('t').toString(), new RangeError('').name]"));
    }

    // Each run gets its own intrinsics, so a patch cannot leak into a later script
    @Test
    public void test_realm_isolation() {
        assertEquals("\"number\"", run("Array.prototype.leaked = 1; return typeof [].leaked"));
        assertEquals("\"undefined\"", run("return typeof [].leaked"));
        assertEquals("\"function\"", run("return typeof [].join"));
    }

    // The promise prototype is a real, patchable object and its patches do not leak between runs
    @Test
    public void test_realm_isolation_covers_promise_proto() {
        assertEquals("[\"object\",\"function\",\"function\",\"function\"]",
                run("return [typeof Promise.prototype, typeof Promise.prototype.then,"
                        + " typeof Promise.prototype.catch, typeof Promise.prototype.finally]"));
        assertEquals("\"leak\"", run(
                "Promise.prototype.then = function () { return 'leak' };" + " return Promise.resolve(1).then(v => v)"));
        assertEquals("\"object\"", run("return typeof Promise.resolve(1).then(v => v)"));
    }

    // A class prototype is a real object whose members are non-enumerable
    @Test
    public void test_class_prototype_is_a_real_object() {
        assertEquals("[\"object\",\"function\",0,true]",
                run("class E { m() {} } return [typeof E.prototype, typeof E.prototype.m,"
                        + " Object.keys(E.prototype).length, E.prototype.constructor === E]"));
    }

    // instanceof against builtin constructors matches objects but never primitives
    @Test
    public void test_instanceof_builtins() {
        assertEquals("[true,true,true,true,false,false,false]",
                run("return [[] instanceof Array, [] instanceof Object, new Map() instanceof Map,"
                        + " /a/ instanceof RegExp, 'a' instanceof String, 1 instanceof Number,"
                        + " true instanceof Boolean]"));
    }

    // Prototype objects carry a constructor back-reference and chain to Object.prototype
    @Test
    public void test_prototype_chain_roots_at_object_prototype() {
        assertEquals("[true,true,true]",
                run("return [Array.prototype.constructor === Array,"
                        + " Object.getPrototypeOf(Array.prototype) === Object.prototype,"
                        + " Object.getPrototypeOf(Object.prototype) === null]"));
    }

    // A getter defined on an intrinsic prototype is invoked with the receiver
    @Test
    public void test_accessor_on_intrinsic_prototype() {
        assertEquals("3",
                run("Object.defineProperty(Array.prototype, 'tail', { get() { return this[this.length-1] } });"
                        + " return [1, 2, 3].tail"));
    }

    // An array assigned as a function's `prototype` is a real link: instances resolve its own
    // elements and, past them, Array.prototype's methods
    @Test
    public void test_function_prototype_may_be_an_array() {
        assertEquals("[\"function\",3,1]",
                run("function foo() {} foo.prototype = new Array(1, 2, 3); const o = new foo();"
                        + " return [typeof o.reduce, o.length, o[0]]"));
        assertEquals("6", run("function foo() {} foo.prototype = new Array(1, 2, 3);"
                + " return new foo().reduce(function (a, b) { return a + b })"));
    }

    // instanceof walks a chain whose links are not plain objects
    @Test
    public void test_instanceof_through_a_non_object_prototype() {
        assertEquals("[true,false]", run("function foo() {} foo.prototype = new Array(1, 2, 3);"
                + " return [new foo() instanceof foo, ({}) instanceof foo]"));
    }

    // A map/date/regexp link resolves its own intrinsic methods too
    @Test
    public void test_other_builtin_values_as_a_prototype() {
        assertEquals("[\"function\",\"function\"]", run("function F() {} F.prototype = new Map(); const m = new F();"
                + " return [typeof m.get, typeof Object.create(new Date(0)).getTime]"));
    }

    // An ordinary function's `prototype` is writable, so a primitive assignment is stored and
    // observable - but it is not a link, so `new` falls back to Object.prototype
    @Test
    public void test_primitive_prototype_assignment_is_stored_but_not_linked() {
        assertEquals("[\"object\",5,true]",
                run("function foo() {} foo.prototype = 5;" + " return [typeof new foo(), foo.prototype,"
                        + " Object.getPrototypeOf(new foo()) === Object.prototype]"));
    }

    // Mutating an intrinsic prototype's own link to a non-object value must not loop forever
    @Test
    public void test_intrinsic_prototype_link_to_an_array_terminates() {
        assertEquals("[\"undefined\",\"undefined\"]", run(
                "Object.setPrototypeOf(Array.prototype, [1, 2]);" + " return [String(({}).nope), String([3].nope)]"));
    }

    // A setter inherited through a non-plain-object link still receives the write
    @Test
    public void test_inherited_setter_through_a_non_object_link() {
        assertEquals("[7,0]",
                run("const seen = []; const link = []; Object.defineProperty(link, 'x', { set(v) { seen.push(v) } });"
                        + " const o = Object.create(link); o.x = 7; return [seen[0], Object.keys(o).length]"));
    }

    // Symbol, BigInt, Boolean and Number instance members resolve through their prototypes
    @Test
    public void test_primitive_prototypes() {
        assertEquals("[\"Symbol(q)\",\"q\",\"ff\",\"true\",\"1.50\"]",
                run("const s = Symbol('q');"
                        + " return [s.toString(), s.description, (255n).toString(16), true.toString(),"
                        + " (1.5).toFixed(2)]"));
    }

    // A plain function's own .prototype object never has its [[Prototype]] explicitly set to
    // Object.prototype, so an instance's chain walk must still reach Object.prototype methods
    // through that intermediate, uninitialised-proto link rather than stopping short of it.
    @Test
    public void test_plain_function_prototype_chain_reaches_object_prototype() {
        assertEquals("[true,true,true]",
                run("function Base() {} const b = new Base(); const d = Object.create(b);"
                        + " return [Object.getPrototypeOf(d) === b, b.isPrototypeOf(d),"
                        + " typeof b.hasOwnProperty === 'function']"));
    }

    // OrdinaryCreateFromConstructor: a generator function's own .prototype set to a non-object
    // must fall back to the intrinsic %GeneratorPrototype%/%AsyncGeneratorPrototype%, not to a
    // null [[Prototype]] on the created instance.
    @Test
    public void test_generator_prototype_falls_back_to_intrinsic_default() {
        assertEquals("true", run("function* g() {} const GeneratorPrototype = Object.getPrototypeOf(g).prototype;"
                + " g.prototype = null; return Object.getPrototypeOf(g()) === GeneratorPrototype"));
    }
}
