package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public class CoroutineTest {
    // park/resume round-trips a yielded value and the value sent back in
    @Test
    public void test_park_resume_round_trip() {
        final var coroutine = new Coroutine();
        coroutine.prime(() -> coroutine.yieldOut(new JsNumber(1)));
        final var first = coroutine.resumeNext(JsUndefined.getInstance());
        assertFalse(first.done());
        assertEquals(1, ((JsNumber) first.value()).getValue());
        final var second = coroutine.resumeNext(new JsNumber(42));
        assertTrue(second.done());
        assertEquals(42, ((JsNumber) second.value()).getValue());
    }

    // resuming a done coroutine returns an undefined, done step
    @Test
    public void test_resume_after_done() {
        final var coroutine = new Coroutine();
        coroutine.prime(JsUndefined::getInstance);
        coroutine.resumeNext(JsUndefined.getInstance());
        final var step = coroutine.resumeNext(JsUndefined.getInstance());
        assertTrue(step.done());
        assertInstanceOf(JsUndefined.class, step.value());
    }

    // resumeThrow injects an uncaught error that escapes the body
    @Test
    public void test_resume_throw_escapes() {
        final var coroutine = new Coroutine();
        coroutine.prime(() -> {
            coroutine.yieldOut(new JsNumber(1));
            return JsUndefined.getInstance();
        });
        coroutine.resumeNext(JsUndefined.getInstance());
        assertThrows(JsThrowException.class, () -> coroutine.resumeThrow(new JsString("boom")));
    }

    // resumeReturn unwinds the body and reports the return value
    @Test
    public void test_resume_return() {
        final var coroutine = new Coroutine();
        coroutine.prime(() -> {
            coroutine.yieldOut(new JsNumber(1));
            return new JsNumber(2);
        });
        coroutine.resumeNext(JsUndefined.getInstance());
        final var step = coroutine.resumeReturn(new JsNumber(99));
        assertTrue(step.done());
        assertEquals(99, ((JsNumber) step.value()).getValue());
    }

    // cancel unwinds a suspended coroutine without running the rest of the body
    @Test
    public void test_cancel_suspended() {
        final var coroutine = new Coroutine();
        final var ran = new boolean[]{false};
        coroutine.prime(() -> {
            coroutine.yieldOut(new JsNumber(1));
            ran[0] = true;
            return JsUndefined.getInstance();
        });
        coroutine.resumeNext(JsUndefined.getInstance());
        coroutine.cancel();
        assertTrue(coroutine.isDone());
        assertFalse(ran[0]);
    }

    // a value written by the body before yielding is visible after resume (happens-before)
    @Test
    public void test_mutual_exclusion_visibility() {
        final var coroutine = new Coroutine();
        final var box = new JsValue[1];
        coroutine.prime(() -> {
            box[0] = new JsNumber(7);
            return coroutine.yieldOut(JsUndefined.getInstance());
        });
        coroutine.resumeNext(JsUndefined.getInstance());
        assertEquals(7, ((JsNumber) box[0]).getValue());
    }

    // return() on a coroutine that never started completes it immediately
    @Test
    public void test_return_before_start() {
        final var coroutine = new Coroutine();
        coroutine.prime(JsUndefined::getInstance);
        final var step = coroutine.resumeReturn(new JsNumber(5));
        assertTrue(step.done());
        assertEquals(5, ((JsNumber) step.value()).getValue());
    }

    // a yield pause reports YIELD and exposes the yielded value
    @Test
    public void test_pause_reason_yield() {
        final var coroutine = new Coroutine();
        coroutine.prime(() -> coroutine.yieldOut(new JsNumber(3)));
        coroutine.resumeNext(JsUndefined.getInstance());
        assertEquals(Coroutine.PauseReason.YIELD, coroutine.pauseReason());
        assertEquals(3, ((JsNumber) coroutine.yieldedValue()).getValue());
    }

    // the resume observer fires after each resume with the escaped error (null on success)
    @Test
    public void test_resume_observer_fires() {
        final var coroutine = new Coroutine();
        final var escapes = new RuntimeException[1];
        final var count = new int[]{0};
        coroutine.setResumeObserver(escaped -> {
            escapes[0] = escaped;
            count[0]++;
        });
        coroutine.prime(() -> {
            coroutine.yieldOut(new JsNumber(1));
            return new JsNumber(2);
        });
        coroutine.resumeNext(JsUndefined.getInstance());
        assertEquals(1, count[0]);
        assertNull(escapes[0]);
        assertEquals(Coroutine.PauseReason.YIELD, coroutine.pauseReason());
        coroutine.resumeNext(JsUndefined.getInstance());
        assertEquals(2, count[0]);
        assertTrue(coroutine.isDone());
        assertEquals(2, ((JsNumber) coroutine.completedValue()).getValue());
    }

    // markAsync flags a coroutine as async-capable
    @Test
    public void test_mark_async() {
        final var coroutine = new Coroutine();
        assertFalse(coroutine.isAsync());
        coroutine.markAsync();
        assertTrue(coroutine.isAsync());
    }
}
