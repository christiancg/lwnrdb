package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;

/**
 * OrdinarySet lands the write on the receiver, not on the object whose chain answered the lookup. Reflect.set
 * is the only way to hand the engine a receiver that is not the target, and a proxy receiver has to reach its
 * traps rather than the ordinary own-property table it does not have.
 */
public class ReceiverAwareSetTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_the_write_lands_on_the_receiver_not_the_target() {
        assertEquals("true:absent:1", str("""
                const target = {};
                const receiver = {};
                [Reflect.set(target, 'x', 1, receiver), 'x' in target ? 'present' : 'absent', receiver.x].join(':')
                """));
    }

    @Test
    public void test_a_non_writable_own_property_on_the_receiver_refuses_the_write() {
        assertEquals("false:0", str("""
                const receiver = {};
                Object.defineProperty(receiver, 'x', { value: 0, writable: false });
                [Reflect.set({}, 'x', 1, receiver), receiver.x].join(':')
                """));
    }

    @Test
    public void test_an_own_accessor_on_the_receiver_refuses_the_write() {
        assertTrue(bool("""
                const receiver = {};
                Object.defineProperty(receiver, 'x', { get() { return 9; } });
                Reflect.set({}, 'x', 1, receiver) === false
                """));
    }

    // A proxy receiver has no ordinary own-property table, so the write has to arrive as a trap
    @Test
    public void test_a_proxy_receiver_defines_the_property_through_its_trap() {
        assertEquals("true:y=2", str("""
                const seen = [];
                const receiver = new Proxy({}, {
                    defineProperty(t, k, d) { seen.push(k + '=' + d.value); return Reflect.defineProperty(t, k, d); }
                });
                [Reflect.set({}, 'y', 2, receiver), seen.join(',')].join(':')
                """));
    }

    @Test
    public void test_a_proxy_receiver_that_already_owns_the_key_is_updated() {
        assertEquals("true:3", str("""
                const receiver = new Proxy({ z: 1 }, {});
                [Reflect.set({}, 'z', 3, receiver), receiver.z].join(':')
                """));
    }

    @Test
    public void test_a_proxy_receiver_with_a_non_writable_key_refuses_the_write() {
        assertTrue(bool("""
                const target = {};
                Object.defineProperty(target, 'w', { value: 1, writable: false });
                Reflect.set({}, 'w', 5, new Proxy(target, {})) === false
                """));
    }

    // An integer-indexed write meant for a foreign receiver leaves an ordinary property there
    @Test
    public void test_a_typed_array_index_written_for_a_foreign_receiver_never_reaches_the_view() {
        assertEquals("true:0:7", str("""
                const view = new Uint8Array(2);
                const receiver = {};
                [Reflect.set(view, '0', 7, receiver), view[0], receiver[0]].join(':')
                """));
    }

    // RegExpExec calls the receiver's own `exec`, so that assignment must land; a flag accessor has no
    // setter and refuses one
    @Test
    public void test_a_regexp_takes_an_own_exec_but_refuses_a_flag_accessor() {
        assertEquals("TypeError:patched", str("""
                const re = /a/g;
                let refused = 'none';
                try { re.global = false; } catch (e) { refused = e.constructor.name; }
                re.exec = () => 'patched';
                [refused, re.exec()].join(':')
                """));
    }

    @Test
    public void test_a_promise_takes_an_own_then_as_a_data_property() {
        assertEquals("patched", str("""
                const promise = Promise.resolve(1);
                promise.then = () => 'patched';
                promise.then()
                """));
    }
}
