package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class DisposableStackBuiltinsTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static String joinArray() {
        final var array = (JsArray) Interpreter.run("let log = [];\nasync function run() {\n    const s = new AsyncDisposableStack();\n    s.defer(() => log.push('a'));\n    s.defer(async () => { log.push('b'); });\n    await s.disposeAsync();\n    log.push('done');\n}\nrun();\nlog\n");
        final var joined = new StringBuilder();
        for (var i = 0; i < array.length(); i++) {
            if (i > 0) {
                joined.append(',');
            }
            joined.append(((JsString) array.get(i)).getValue());
        }
        return joined.toString();
    }

    // deferred callbacks run in reverse registration order
    @Test
    public void test_defer_runs_in_reverse_order() {
        final var source = """
                const s = new DisposableStack();
                let log = [];
                s.defer(() => log.push('a'));
                s.defer(() => log.push('b'));
                s.dispose();
                log.join(',')
                """;
        assertEquals("b,a", str(source));
    }

    // use returns its argument and disposes it
    @Test
    public void test_use_returns_and_disposes() {
        final var source = """
                let log = [];
                const s = new DisposableStack();
                const r = s.use({ value: 7, [Symbol.dispose]() { log.push('d'); } });
                s.dispose();
                r.value + ':' + log.join(',')
                """;
        assertEquals("7:d", str(source));
    }

    // a nullish resource is a no-op
    @Test
    public void test_use_nullish_is_noop() {
        assertEquals(0, num("const s = new DisposableStack(); s.use(null); s.use(undefined); s.dispose(); 0"));
    }

    // a non-disposable resource is rejected
    @Test
    public void test_use_non_disposable_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new DisposableStack().use({})"));
    }

    // adopt pairs a value with an explicit disposer
    @Test
    public void test_adopt() {
        final var source = """
                let log = [];
                const s = new DisposableStack();
                const r = s.adopt(3, v => log.push('d' + v));
                s.dispose();
                r + ':' + log.join(',')
                """;
        assertEquals("3:d3", str(source));
    }

    // dispose is idempotent
    @Test
    public void test_dispose_is_idempotent() {
        final var source = """
                let count = 0;
                const s = new DisposableStack();
                s.defer(() => count++);
                s.dispose();
                s.dispose();
                count
                """;
        assertEquals(1, num(source));
    }

    // the disposed getter reflects the stack's state
    @Test
    public void test_disposed_getter() {
        assertTrue(
                bool("const s = new DisposableStack(); const before = s.disposed; s.dispose(); !before && s.disposed"));
    }

    // move transfers the entries and disposes the source handle
    @Test
    public void test_move_transfers_entries() {
        final var source = """
                let log = [];
                const s = new DisposableStack();
                s.defer(() => log.push('d'));
                const moved = s.move();
                s.dispose();
                const afterSource = log.length;
                moved.dispose();
                afterSource + ':' + log.join(',') + ':' + s.disposed
                """;
        assertEquals("0:d:true", str(source));
    }

    // registering on a disposed stack is a ReferenceError
    @Test
    public void test_register_after_dispose_throws() {
        assertThrows(ReferenceErrorException.class,
                () -> Interpreter.run("const s = new DisposableStack(); s.dispose(); s.defer(() => {});"));
        assertThrows(ReferenceErrorException.class,
                () -> Interpreter.run("const s = new DisposableStack(); s.dispose(); s.move();"));
    }

    // two throwing disposers aggregate into a SuppressedError with the newest as error
    @Test
    public void test_suppressed_error_aggregation() {
        final var source = """
                const s = new DisposableStack();
                s.defer(() => { throw new Error('first'); });
                s.defer(() => { throw new Error('second'); });
                let caught = null;
                try { s.dispose(); } catch (e) { caught = e; }
                caught.name + ':' + caught.error.message + ':' + caught.suppressed.message
                """;
        assertEquals("SuppressedError:first:second", str(source));
    }

    // disposeAsync resolves after every entry has run
    @Test
    public void test_dispose_async_resolves_after_entries() {
        assertEquals("b,a,done", joinArray());
    }

    // the async stack exposes Symbol.asyncDispose on its prototype
    @Test
    public void test_async_stack_prototype_has_async_dispose() {
        assertTrue(bool("Symbol.asyncDispose in AsyncDisposableStack.prototype"));
    }

    // a prototype method rejects a foreign receiver
    @Test
    public void test_foreign_receiver_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("DisposableStack.prototype.dispose.call({})"));
    }
}
