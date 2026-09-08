package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class GlobalSurfaceProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // Reflect.set with a receiver writes to the receiver, not the target
    @Test
    public void test_reflect_set_with_a_receiver() {
        final var source = """
                const target = {};
                const other = {};
                Reflect.set(target, 'a', 1, other);
                String(target.a) + ':' + other.a
                """;
        assertEquals("undefined:1", str(source));
    }

    // Reflect.set reports false for a non-writable property
    @Test
    public void test_reflect_set_on_a_non_writable_property() {
        final var source = """
                const o = {};
                Object.defineProperty(o, 'a', { value: 1, writable: false });
                Reflect.set(o, 'a', 2)
                """;
        assertFalse(bool(source));
    }

    // Reflect.set on a typed array index with a foreign receiver leaves the view alone
    @Test
    public void test_reflect_set_on_a_typed_array_with_a_receiver() {
        assertEquals("true:0", str("const t = new Int8Array(2); String(Reflect.set(t, '0', 5, {})) + ':' + t[0]"));
    }

    // Reflect.get with a receiver binds the getter to that receiver
    @Test
    public void test_reflect_get_with_a_receiver() {
        final var source = """
                const proto = { get a() { return this.tag; } };
                const o = Object.create(proto);
                Reflect.get(o, 'a', { tag: 'r' })
                """;
        assertEquals("r", str(source));
    }

    // Shrinking an array through length drops the trailing elements
    @Test
    public void test_array_length_assignment_shrinks() {
        assertEquals("1", str("const a = [1, 2, 3]; a.length = 1; a.join(',')"));
    }

    // Growing an array through length appends holes
    @Test
    public void test_array_length_assignment_grows() {
        assertEquals("3:undefined", str("const a = [1]; a.length = 3; a.length + ':' + String(a[2])"));
    }

    // defineProperty can set the array length too
    @Test
    public void test_define_property_on_array_length() {
        assertEquals("1", str("const a = [1, 2]; Object.defineProperty(a, 'length', { value: 1 }); a.join(',')"));
    }

    // A proxy get trap receives a symbol key as a symbol
    @Test
    public void test_proxy_get_trap_receives_a_symbol() {
        final var source = """
                const key = Symbol('k');
                const p = new Proxy({}, { get(target, k) { return typeof k === 'symbol' ? 'sym' : 'str'; } });
                p[key]
                """;
        assertEquals("sym", str(source));
    }

    // propertyIsEnumerable distinguishes enumerable own properties
    @Test
    public void test_property_is_enumerable() {
        final var source = """
                const o = { a: 1 };
                Object.defineProperty(o, 'b', { value: 2, enumerable: false });
                o.propertyIsEnumerable('a') + ':' + o.propertyIsEnumerable('b')
                """;
        assertEquals("true:false", str(source));
    }

    // isPrototypeOf walks the whole chain
    @Test
    public void test_is_prototype_of() {
        assertTrue(bool("Object.prototype.isPrototypeOf([])"));
        assertTrue(bool("Array.prototype.isPrototypeOf([])"));
    }

    // Object.prototype.valueOf boxes a primitive receiver
    @Test
    public void test_object_value_of_over_a_primitive() {
        assertTrue(bool("Object.prototype.valueOf.call(1) == 1"));
    }

    // A custom toStringTag is reported by Object.prototype.toString
    @Test
    public void test_custom_to_string_tag() {
        assertEquals("[object Custom]", str("Object.prototype.toString.call({ [Symbol.toStringTag]: 'Custom' })"));
    }

    // structuredClone copies nested structures
    @Test
    public void test_structured_clone() {
        assertEquals(2, num("structuredClone({ a: [1, { b: 2 }] }).a[1].b"));
    }

    // structuredClone preserves a cycle
    @Test
    public void test_structured_clone_of_a_cycle() {
        assertTrue(bool("const o = {}; o.self = o; const c = structuredClone(o); c.self === c"));
    }

    // A function cannot be cloned
    @Test
    public void test_structured_clone_rejects_a_function() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("structuredClone(() => {})"));
    }

    // queueMicrotask runs its callback while the event loop drains
    @Test
    public void test_queue_microtask() {
        final var array = (JsArray) Interpreter.run("let out = []; queueMicrotask(() => out.push(1)); out");
        assertEquals(1, array.length());
    }

    // encodeURIComponent escapes more than encodeURI
    @Test
    public void test_encode_uri_functions() {
        assertEquals("a%20b%2Fc:a%20b/c", str("encodeURIComponent('a b/c') + ':' + encodeURI('a b/c')"));
    }

    // The decode functions reverse the escaping
    @Test
    public void test_decode_uri_functions() {
        assertEquals("a b:a b", str("decodeURIComponent('a%20b') + ':' + decodeURI('a%20b')"));
    }

    // A malformed escape sequence is a URIError
    @Test
    public void test_decode_uri_rejects_a_malformed_sequence() {
        final var source = """
                let name = '';
                try { decodeURIComponent('%'); } catch (e) { name = e.constructor.name; }
                name
                """;
        assertEquals("URIError", str(source));
    }

    // The Annex B escape functions round-trip
    @Test
    public void test_escape_and_unescape() {
        assertEquals("a%20b:a b", str("escape('a b') + ':' + unescape('a%20b')"));
    }

    // Math.sumPrecise rounds the exact sum once
    @Test
    public void test_math_sum_precise() {
        assertEquals(0.30000000000000004, num("Math.sumPrecise([0.1, 0.2])"));
    }

    // Math.f16round narrows to half precision
    @Test
    public void test_math_f16round() {
        assertEquals(1.3369140625, num("Math.f16round(1.337)"));
    }

    // The integer helpers of Math follow their spec definitions
    @Test
    public void test_math_integer_helpers() {
        assertEquals("12:31:-1", str("Math.imul(3, 4) + ':' + Math.clz32(1) + ':' + Math.trunc(-1.5)"));
    }

    // A DisposableStack runs its deferred callbacks at scope exit
    @Test
    public void test_disposable_stack_defer() {
        final var source = """
                let out = '';
                {
                    using stack = new DisposableStack();
                    stack.defer(() => { out += 'd'; });
                    out += 'b';
                }
                out
                """;
        assertEquals("bd", str(source));
    }

    // A DisposableStack disposes the resources it adopts
    @Test
    public void test_disposable_stack_use() {
        final var source = """
                let out = '';
                {
                    using stack = new DisposableStack();
                    stack.use({ [Symbol.dispose]() { out += 'u'; } });
                }
                out
                """;
        assertEquals("u", str(source));
    }

    // BigInt methods render and wrap values
    @Test
    public void test_bigint_methods() {
        assertEquals("ff:1:-1",
                str("(255n).toString(16) + ':' + BigInt.asUintN(8, 257n) + ':' + BigInt.asIntN(8, 255n)"));
    }

    // toFixed past the exponential threshold falls back to the plain number string
    @Test
    public void test_number_to_fixed_beyond_the_threshold() {
        assertEquals("1e+21", str("(1e21).toFixed(2)"));
    }

    // A radix conversion covers integral and fractional parts
    @Test
    public void test_number_radix_conversion() {
        assertEquals("ff:0.1", str("(255).toString(16) + ':' + (0.5).toString(2)"));
    }

    // A symbol carries its description and stringifies with it
    @Test
    public void test_symbol_description() {
        assertEquals("d:Symbol(d)", str("Symbol('d').description + ':' + Symbol('d').toString()"));
    }

    // The symbol registry returns the same symbol for a key
    @Test
    public void test_symbol_registry() {
        assertEquals("true:x",
                str("String(Symbol.for('x') === Symbol.for('x')) + ':' + Symbol.keyFor(Symbol.for('x'))"));
    }

    // An error carries the cause it was constructed with
    @Test
    public void test_error_cause() {
        assertEquals("c", str("new Error('m', { cause: 'c' }).cause"));
    }

    // Error.isError brand-checks an error object
    @Test
    public void test_error_is_error() {
        assertTrue(bool("Error.isError(new TypeError('x'))"));
        assertFalse(bool("Error.isError({})"));
    }
}
