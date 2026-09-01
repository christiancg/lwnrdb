package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.ScriptLoad;

public class ScriptLoadTest {

    @Test
    public void test_enters_and_exits_symmetrically() {
        final var load = new ScriptLoad();
        assertEquals(0, load.current());
        load.enter();
        assertEquals(1, load.current());
        load.enter();
        assertEquals(2, load.current());
        load.exit();
        assertEquals(1, load.current());
        load.exit();
        assertEquals(0, load.current());
    }

    @Test
    public void test_exit_on_failure_still_decrements() {
        final var load = new ScriptLoad();
        assertThrows(IllegalStateException.class, () -> {
            load.enter();
            try {
                throw new IllegalStateException("boom");
            } finally {
                load.exit();
            }
        });
        assertEquals(0, load.current());
    }

    @Test
    public void test_concurrent_runs_never_go_negative_and_return_to_zero() throws Exception {
        final var load = new ScriptLoad();
        final var threads = 8;
        final var start = new CountDownLatch(1);
        final var done = new CountDownLatch(threads);
        final var lowest = new AtomicInteger(Integer.MAX_VALUE);
        for (var i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (var run = 0; run < 500; run++) {
                        load.enter();
                        lowest.getAndUpdate(current -> Math.min(current, load.current()));
                        load.exit();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertEquals(0, load.current());
        assertTrue(lowest.get() >= 1, "the load was observed below 1 while a run was in flight: " + lowest.get());
    }
}
