package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.exceptions.ScriptTimeoutException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

// Property access dispatch (MemberEvaluator): symbol-keyed member lookups on exotic value types,
// class instance/static symbol tables, receiver-aware get/set delegation (Reflect), and the
// async-generator step-settlement paths (resolve/reject on next/return/throw).
public class InterpreterMemberTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool() {
        return ((JsBoolean) Interpreter
                .run("let a = [1, 2, 3]; let r = {}; Reflect.set(a, '0', 9, r); a[0] === 1 && r[0] === 9")).getValue();
    }

    // reads the accumulator array reference after the event loop has drained
    private static String joined(String source) {
        final var array = (JsArray) Interpreter.run(source);
        final var sb = new StringBuilder();
        for (var i = 0; i < array.length(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(JsCoercion.toStr(array.get(i)));
        }
        return sb.toString();
    }

    // A typed array's [Symbol.iterator] member is a callable that yields its elements
    @Test
    public void test_typed_array_symbol_iterator_member() {
        final var source = """
                const arr = new Int8Array([1, 2, 3]);
                const it = arr[Symbol.iterator]();
                let out = [];
                let r;
                while (!(r = it.next()).done) { out.push(r.value); }
                out.join(',')
                """;
        assertEquals("1,2,3", str(source));
    }

    // A class-defined symbol-keyed instance getter is found through the class symbol table
    @Test
    public void test_instance_symbol_getter_via_class_table() {
        final var source = """
                class C { get [Symbol.for('x')]() { return 42; } }
                (new C())[Symbol.for('x')]
                """;
        assertEquals(42, num(source));
    }

    // A class-defined symbol-keyed static getter is found through the class symbol table
    @Test
    public void test_static_symbol_getter_via_class_table() {
        final var source = """
                class A { static get [Symbol.for('k')]() { return 5; } }
                A[Symbol.for('k')]
                """;
        assertEquals(5, num(source));
    }

    // Reflect.get with an explicit receiver on a non-JsObject target (an array) still resolves
    @Test
    public void test_reflect_get_on_array_with_receiver_delegates() {
        assertEquals(2, num("Reflect.get([1, 2, 3], '1', {})"));
    }

    // Reading any property off undefined throws a catchable TypeError
    @Test
    public void test_reading_property_of_undefined_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("undefined.foo"));
    }

    // A deleted builtin-function metadata property (name/length) reads back as undefined
    @Test
    public void test_deleted_function_metadata_reads_undefined() {
        assertEquals("undefined", str("delete parseInt.name; typeof parseInt.name"));
    }

    // A typed array exposes BYTES_PER_ELEMENT per its kind
    @Test
    public void test_typed_array_bytes_per_element() {
        assertEquals(1, num("new Int8Array(1).BYTES_PER_ELEMENT"));
        assertEquals(4, num("new Int32Array(1).BYTES_PER_ELEMENT"));
    }

    // Reflect.set with an explicit receiver writes to the receiver, leaving the array target alone
    @Test
    public void test_reflect_set_on_array_with_receiver_delegates() {
        assertTrue(bool());
    }

    // Setting any property on undefined throws a catchable TypeError
    @Test
    public void test_setting_property_of_undefined_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("undefined.x = 1;"));
    }

    // Writing a property on an exotic primitive-backed value (no JsObject wrapper) is a silent no-op
    @Test
    public void test_writing_property_on_number_is_noop() {
        assertEquals("undefined", str("let n = 5; n.foo = 9; typeof n.foo"));
    }

    // Calling throw() on an async generator that already ran to completion rejects immediately
    @Test
    public void test_async_generator_throw_after_completion_rejects() {
        final var source = """
                let out = [];
                async function* g() { yield 1; }
                const it = g();
                it.next().then(() => it.next()).then(() => it.throw('boom')).then(
                    v => out.push('resolved'),
                    e => out.push('err:' + e));
                out
                """;
        assertEquals("err:boom", joined(source));
    }

    // A synchronous throw before any yield/await rejects the first next() step, via the
    // coroutine's resume observer (observeAsyncGenerator), which always intercepts before
    // driveAsyncGenerator's own try/catch would ever see the escape
    @Test
    public void test_sync_throw_in_async_generator_rejects_step() {
        final var source = """
                let out = [];
                async function* g() { throw new Error('boom'); }
                g().next().then(v => out.push('resolved'), e => out.push('err:' + e.message));
                out
                """;
        assertEquals("err:boom", joined(source));
    }

    // A finally-block error thrown while cancelling a suspended generator with no pending step is
    // swallowed rather than escaping the interpreter
    @Test
    public void test_cancel_with_no_pending_promise_is_swallowed() {
        final var source = """
                let out = [];
                async function* g() {
                    yield 1;
                    try {
                        yield 2;
                    } finally {
                        out.push('cleanup');
                        throw new Error('boom');
                    }
                }
                const it = g();
                it.next();
                it.next();
                out
                """;
        assertEquals("cleanup", joined(source));
    }

    // An unrecognized runtime escape (a resource-limit abort) after an await resumption is
    // re-thrown rather than settled as a rejected step
    @Test
    public void test_unrecognized_escape_after_await_propagates() {
        final var limits = new ResourceLimits(-1, 50, -1, true);
        final var source = """
                async function* g() {
                    await Promise.resolve(1);
                    while (true) {}
                }
                g().next();
                """;
        assertThrows(ScriptTimeoutException.class,
                () -> Interpreter.run(source, new SimpleHostBindings(new JsonObject(), null, null, limits)));
    }
}
