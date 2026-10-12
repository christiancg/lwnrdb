package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.exceptions.ScriptCancelledException;
import org.techhouse.simplejs.exceptions.ScriptLimitException;
import org.techhouse.simplejs.exceptions.ScriptTimeoutException;
import org.techhouse.simplejs.host.CancellationToken;
import org.techhouse.simplejs.host.DatabaseAccess;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.internal.Session;

public class RunLifecycleTickTest {
    private record Host(ResourceLimits limits, CancellationToken cancellation) implements HostBindings {
        @Override
        public JsonObject args() {
            return new JsonObject();
        }

        @Override
        public DatabaseAccess database() {
            return null;
        }

        @Override
        public Consumer<String> console() {
            return null;
        }
    }

    private static Session open(ResourceLimits limits, CancellationToken cancellation) {
        return Interpreter.open(Parser.parse(Lexer.lexWithPositions("1")), new Host(limits, cancellation));
    }

    @Test
    public void test_weighted_tick_decrements_the_budget_and_counts_instructions() {
        try (var session = open(new ResourceLimits(10_000, -1, -1, true), null)) {
            final var interpreter = session.interpreter;
            final var remaining = interpreter.instructionsRemaining;
            final var used = interpreter.instructionsUsed;
            interpreter.tick(1024);
            assertEquals(remaining - 1024, interpreter.instructionsRemaining);
            assertEquals(used + 1024, interpreter.instructionsUsed);
        }
    }

    @Test
    public void test_weighted_tick_throws_when_the_budget_cannot_cover_it() {
        try (var session = open(new ResourceLimits(10_000, -1, -1, true), null)) {
            final var interpreter = session.interpreter;
            interpreter.instructionsRemaining = 1000;
            assertThrows(ScriptLimitException.class, () -> interpreter.tick(1024));
            assertEquals(0, interpreter.instructionsRemaining);
            assertThrows(ScriptLimitException.class, interpreter::tick);
        }
    }

    @Test
    public void test_weighted_tick_checks_cancellation_on_every_call() {
        final var cancelled = new AtomicBoolean();
        try (var session = open(new ResourceLimits(-1, -1, -1, true), cancelled::get)) {
            final var interpreter = session.interpreter;
            interpreter.tick(1024);
            cancelled.set(true);
            assertThrows(ScriptCancelledException.class, () -> interpreter.tick(1024));
        }
    }

    @Test
    public void test_weighted_tick_checks_the_deadline_on_every_call() throws InterruptedException {
        try (var session = open(new ResourceLimits(-1, 50, -1, true), null)) {
            final var interpreter = session.interpreter;
            Thread.sleep(100);
            assertThrows(ScriptTimeoutException.class, () -> interpreter.tick(1));
        }
    }

    @Test
    public void test_unweighted_tick_is_unchanged() {
        try (var session = open(new ResourceLimits(10_000, 50, -1, true), null)) {
            final var interpreter = session.interpreter;
            final var remaining = interpreter.instructionsRemaining;
            interpreter.instructionsUsed = 1;
            interpreter.tick();
            assertEquals(remaining - 1, interpreter.instructionsRemaining);
            assertEquals(2, interpreter.instructionsUsed);
        }
    }

    @Test
    public void test_unlimited_budget_only_counts() {
        try (var session = open(ResourceLimits.unlimited(), null)) {
            final var interpreter = session.interpreter;
            final var used = interpreter.instructionsUsed;
            interpreter.tick(1024);
            assertEquals(-1, interpreter.instructionsRemaining);
            assertEquals(used + 1024, interpreter.instructionsUsed);
        }
    }
}
