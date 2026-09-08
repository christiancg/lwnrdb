package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ScriptLoad;
import org.techhouse.ops.ScriptRunKind;
import org.techhouse.ops.ScriptRunRegistry;

public class ScriptLoadTest {
    private final ScriptRunRegistry registry = IocContainer.get(ScriptRunRegistry.class);
    private final ScriptLoad load = new ScriptLoad();

    @AfterEach
    public void cleanUp() {
        registry.list().forEach(run -> registry.unregister(run.runId()));
    }

    private String register() {
        return registry.register(ScriptRunKind.RUN_SCRIPT, "db", null, "u", null).runId();
    }

    // The gossiped signal is the registry's size, so the number placement acts on and the runs an operator
    // sees can never disagree
    @Test
    public void test_reports_the_number_of_registered_runs() {
        assertEquals(0, load.current());
        final var first = register();
        assertEquals(1, load.current());
        final var second = register();
        assertEquals(2, load.current());
        registry.unregister(second);
        assertEquals(1, load.current());
        registry.unregister(first);
        assertEquals(0, load.current());
    }

    @Test
    public void test_returns_to_zero_when_a_run_fails() {
        final var runId = register();
        try {
            throw new IllegalStateException("boom");
        } catch (IllegalStateException ignored) {
            registry.unregister(runId);
        }
        assertEquals(0, load.current());
    }

    @Test
    public void test_concurrent_runs_never_go_negative_and_return_to_zero() throws Exception {
        final var threads = 8;
        final var start = new CountDownLatch(1);
        final var done = new CountDownLatch(threads);
        final var lowest = new AtomicInteger(Integer.MAX_VALUE);
        final var workers = new ArrayList<Thread>();
        for (var i = 0; i < threads; i++) {
            workers.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (var run = 0; run < 500; run++) {
                        final var runId = register();
                        lowest.getAndUpdate(current -> Math.min(current, load.current()));
                        registry.unregister(runId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }));
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        for (final var worker : workers) {
            worker.join();
        }
        assertEquals(0, load.current());
        assertTrue(lowest.get() >= 1, "the load was observed below 1 while a run was in flight: " + lowest.get());
    }
}
