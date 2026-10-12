package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.test.TestUtils;

public class CoroutineTest {
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

    @Test
    public void test_resume_after_done() {
        final var coroutine = new Coroutine();
        coroutine.prime(JsUndefined::getInstance);
        coroutine.resumeNext(JsUndefined.getInstance());
        final var step = coroutine.resumeNext(JsUndefined.getInstance());
        assertTrue(step.done());
        assertInstanceOf(JsUndefined.class, step.value());
    }

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

    @Test
    public void test_return_before_start() {
        final var coroutine = new Coroutine();
        coroutine.prime(JsUndefined::getInstance);
        final var step = coroutine.resumeReturn(new JsNumber(5));
        assertTrue(step.done());
        assertEquals(5, ((JsNumber) step.value()).getValue());
    }

    @Test
    public void test_pause_reason_yield() {
        final var coroutine = new Coroutine();
        coroutine.prime(() -> coroutine.yieldOut(new JsNumber(3)));
        coroutine.resumeNext(JsUndefined.getInstance());
        assertEquals(Coroutine.PauseReason.YIELD, coroutine.pauseReason());
        assertEquals(3, ((JsNumber) coroutine.yieldedValue()).getValue());
    }

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

    @Test
    public void test_mark_async() {
        final var coroutine = new Coroutine();
        assertFalse(coroutine.isAsync());
        coroutine.markAsync();
        assertTrue(coroutine.isAsync());
    }

    @Test
    public void test_reentrant_resume_throws_type_error_instead_of_deadlocking() {
        final var coroutine = new Coroutine();
        final var reentrant = new RuntimeException[1];
        coroutine.prime(() -> {
            reentrant[0] = assertThrows(TypeErrorException.class,
                    () -> coroutine.resumeNext(JsUndefined.getInstance()));
            return new JsNumber(1);
        });
        final var step = coroutine.resumeNext(JsUndefined.getInstance());
        assertTrue(step.done());
        assertNotNull(reentrant[0]);
    }

    @Test
    public void test_delegated_yield_marker_is_one_shot() {
        final var coroutine = new Coroutine();
        final var flags = new boolean[2];
        coroutine.prime(() -> {
            coroutine.markDelegatedYield();
            coroutine.yieldOut(new JsNumber(1));
            coroutine.yieldOut(new JsNumber(2));
            return JsUndefined.getInstance();
        });
        coroutine.resumeNext(JsUndefined.getInstance());
        flags[0] = coroutine.isDelegatedYield();
        coroutine.resumeNext(JsUndefined.getInstance());
        flags[1] = coroutine.isDelegatedYield();
        assertTrue(flags[0]);
        assertFalse(flags[1]);
    }

    @Test
    public void test_error_in_body_is_rethrown_to_driver() {
        final var coroutine = new Coroutine();
        coroutine.prime(() -> {
            throw new StackOverflowError("deep");
        });
        final var thrown = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertThrows(StackOverflowError.class, () -> coroutine.resumeNext(JsUndefined.getInstance())));
        assertEquals("deep", thrown.getMessage());
        assertTrue(coroutine.isDone());
    }

    @Test
    public void test_out_of_memory_in_body_is_rethrown() {
        final var coroutine = new Coroutine();
        final Coroutine.Body body = () -> {
            throw new OutOfMemoryError("heap");
        };
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertThrows(OutOfMemoryError.class, () -> coroutine.startAsync(body)));
        assertTrue(coroutine.isDone());
    }

    @Test
    public void test_error_in_generator_resume_is_not_swallowed_by_observer() {
        final var coroutine = new Coroutine();
        final var observed = new int[1];
        coroutine.setResumeObserver(_ -> observed[0]++);
        coroutine.prime(() -> {
            throw new StackOverflowError();
        });
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertThrows(StackOverflowError.class, () -> coroutine.resumeNext(JsUndefined.getInstance())));
        assertEquals(0, observed[0]);
    }

    @Test
    public void test_error_after_yield_reaches_resumer() {
        final var coroutine = new Coroutine();
        coroutine.prime(() -> {
            coroutine.yieldOut(new JsNumber(1));
            throw new StackOverflowError();
        });
        assertFalse(coroutine.resumeNext(JsUndefined.getInstance()).done());
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertThrows(StackOverflowError.class, () -> coroutine.resumeNext(JsUndefined.getInstance())));
        assertTrue(coroutine.isDone());
        assertTrue(coroutine.resumeNext(JsUndefined.getInstance()).done());
    }

    @Test
    public void test_interrupt_before_first_turn_releases_driver() throws Exception {
        final var coroutine = new Coroutine();
        coroutine.prime(() -> new JsNumber(1));
        final var threadMain = Coroutine.class.getDeclaredMethod("threadMain");
        threadMain.setAccessible(true);
        final var interruptedAfter = new boolean[1];
        final var worker = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            try {
                threadMain.invoke(coroutine);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
            interruptedAfter[0] = Thread.currentThread().isInterrupted();
        });
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertTrue(coroutine.isDone());
        assertTrue(interruptedAfter[0]);
        assertTrue(TestUtils.getPrivateField(coroutine, "exited", Boolean.class));
    }
}
