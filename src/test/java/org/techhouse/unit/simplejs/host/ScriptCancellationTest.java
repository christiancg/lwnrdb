package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.CancellationToken;
import org.techhouse.simplejs.host.DatabaseAccess;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;

public class ScriptCancellationTest {
    private static final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);

    // The engine-level seam: everything else about the sandbox is the standalone default.
    private record CancellableBindings(ResourceLimits limits, CancellationToken cancellation,
            Consumer<String> console) implements HostBindings {

        @Override
        public JsonObject args() {
            return new JsonObject();
        }

        @Override
        public DatabaseAccess database() {
            return null;
        }
    }

    private static ScriptResult run(String source, CancellationToken token, long wallClockMillis) {
        final var limits = new ResourceLimits(-1, wallClockMillis, -1, true);
        return simpleJs.run(source, new CancellableBindings(limits, token, null));
    }

    // Cancellation is reported as its own outcome, not as the timeout it would otherwise become
    @Test
    public void test_busy_loop_is_cancelled() throws Exception {
        final var cancelled = new AtomicBoolean();
        final var running = new CountDownLatch(1);
        final var result = new ScriptResult[1];
        final var runner = Thread.ofVirtual().start(() -> {
            running.countDown();
            result[0] = run("while (true) { }", cancelled::get, 30_000L);
        });
        assertTrue(running.await(30, TimeUnit.SECONDS));
        Thread.sleep(100);
        cancelled.set(true);
        runner.join(30_000L);
        assertTrue(result[0].isError());
        assertEquals("ScriptCancelledError", result[0].getErrorName());
        assertEquals("Script was cancelled", result[0].getErrorMessage());
    }

    // The ScriptAbortException property: a script cannot trap its own cancellation
    @Test
    public void test_cancellation_is_not_catchable() {
        final var result = run("try { while (true) { } } catch (e) { return 'caught'; }", () -> true, -1);
        assertTrue(result.isError());
        assertEquals("ScriptCancelledError", result.getErrorName());
    }

    @Test
    public void test_finally_is_skipped_on_cancellation() {
        final var result = run("""
                try {
                    while (true) { }
                } finally {
                    console.log('finalizer ran');
                }
                """, () -> true, -1);
        assertEquals("ScriptCancelledError", result.getErrorName());
        assertFalse(result.getLogs().contains("finalizer ran"), "the finally block ran on the way out");
    }

    // Asserts the bounded park: with no wall clock the loop used to park until the timer came due
    @Test
    public void test_script_parked_on_timer_is_cancelled() throws Exception {
        final var cancelled = new AtomicBoolean();
        final var running = new CountDownLatch(1);
        final var result = new ScriptResult[1];
        final var runner = Thread.ofVirtual().start(() -> {
            running.countDown();
            result[0] = run("export default new Promise(r => setTimeout(r, 30000));", cancelled::get, -1);
        });
        assertTrue(running.await(30, TimeUnit.SECONDS));
        Thread.sleep(200);
        cancelled.set(true);
        runner.join(15_000L);
        assertFalse(runner.isAlive(), "the parked script did not notice its cancellation");
        assertEquals("ScriptCancelledError", result[0].getErrorName());
    }

    // Cancellation is cooperative: a token already set when the run starts stops it at the first tick,
    // which is a loop back-edge or a call entry
    @Test
    public void test_already_cancelled_token_aborts_immediately() {
        final var result = run("let total = 0; for (let i = 0; i < 10; i++) { total += i; } return total;", () -> true,
                -1);
        assertTrue(result.isError());
        assertEquals("ScriptCancelledError", result.getErrorName());
    }

    // Nothing to interrupt means nothing is interrupted: a straight-line program never reaches a tick
    @Test
    public void test_straight_line_script_finishes_despite_a_set_token() {
        final var result = run("return 1 + 1;", () -> true, -1);
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(2, result.getValue().asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_null_token_behaves_as_before() {
        final var result = run("return 1 + 1;", null, -1);
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(2, result.getValue().asJsonNumber().getValue().intValue());
    }

    // A cancelled run still reports the console output it produced before it was stopped
    @Test
    public void test_keeps_the_logs_produced_before_the_cancellation() throws Exception {
        final var cancelled = new AtomicBoolean();
        final var running = new CountDownLatch(1);
        final var result = new ScriptResult[1];
        final var runner = Thread.ofVirtual().start(() -> {
            running.countDown();
            result[0] = run("console.log('before'); while (true) { }", cancelled::get, 30_000L);
        });
        assertTrue(running.await(30, TimeUnit.SECONDS));
        Thread.sleep(100);
        cancelled.set(true);
        runner.join(30_000L);
        assertEquals("ScriptCancelledError", result[0].getErrorName());
        assertTrue(result[0].getLogs().contains("before"));
    }
}
