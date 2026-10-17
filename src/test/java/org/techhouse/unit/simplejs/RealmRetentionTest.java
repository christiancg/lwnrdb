package org.techhouse.unit.simplejs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsValue;

public class RealmRetentionTest {
    private static final int GC_ATTEMPTS = 50;
    private static final long GC_PAUSE_MILLIS = 20L;
    private static final ReferenceQueue<JsValue> CLEARED = new ReferenceQueue<>();

    private static JsValue runOnOwnThread(String source) throws InterruptedException {
        final var returned = new AtomicReference<JsValue>();
        final var worker = Thread.ofPlatform()
                .start(() -> returned.set(Interpreter.run(source, SimpleHostBindings.empty()).returnValue()));
        worker.join();
        return returned.get();
    }

    private static WeakReference<JsValue> weakResultOf(String source) throws InterruptedException {
        return new WeakReference<>(runOnOwnThread(source), CLEARED);
    }

    @SuppressWarnings("PMD.DoNotCallGarbageCollectionExplicitly")
    private static boolean collected(WeakReference<?> reference) throws InterruptedException {
        for (var attempt = 0; attempt < GC_ATTEMPTS && reference.get() != null; attempt++) {
            System.gc();
            CLEARED.remove(GC_PAUSE_MILLIS);
        }
        return reference.get() == null;
    }

    @Test
    public void test_running_a_script_does_not_retain_its_interpreter() throws InterruptedException {
        final var helper = weakResultOf("globalThis.keep = [1, 2].values().map(x => x); keep.next(); return keep;");
        final var wrapper = weakResultOf(
                "globalThis.keep = Iterator.from({ next() { return { done: true }; } }); return keep;");
        final var parse = weakResultOf("globalThis.keep = [parseInt, parseFloat]; return keep;");
        final var lazy = weakResultOf("globalThis.keep = new Map([[1, 2]]).keys(); return keep;");
        assertTrue(collected(helper), "an iterator helper kept its realm alive");
        assertTrue(collected(wrapper), "an Iterator.from wrapper kept its realm alive");
        assertTrue(collected(parse), "parseInt/parseFloat kept their realm alive");
        assertTrue(collected(lazy), "a built-in iterator kept its realm alive");
    }

    @Test
    public void test_parse_float_function_is_per_realm() throws InterruptedException {
        final var first = runOnOwnThread("return parseFloat;");
        final var second = runOnOwnThread("return parseFloat;");
        assertNotSame(first, second);
    }

    @Test
    public void test_the_global_parse_functions_are_the_number_statics() throws InterruptedException {
        final var result = runOnOwnThread("return [Number.parseInt === parseInt, Number.parseFloat === parseFloat,"
                + " parseInt('42px'), parseFloat('2.5e1')].join();");
        assertEquals("true,true,42,25", ((JsString) result).getValue());
    }

    @Test
    public void test_iterator_helpers_still_reject_a_foreign_receiver() throws InterruptedException {
        final var result = runOnOwnThread(
                "const helper = [1].values().map(x => x);" + " const next = Object.getPrototypeOf(helper).next;"
                        + " try { next.call({}); return 'accepted'; } catch (e) { return e.name; }");
        assertEquals("TypeError", ((JsString) result).getValue());
    }
}
