package org.techhouse.simplejs.internal;

import static org.techhouse.simplejs.host.ScriptErrorNames.TIMED_OUT_MESSAGE;

import org.techhouse.simplejs.exceptions.ScriptCancelledException;
import org.techhouse.simplejs.exceptions.ScriptLimitException;
import org.techhouse.simplejs.exceptions.ScriptMemoryException;
import org.techhouse.simplejs.exceptions.ScriptTimeoutException;
import org.techhouse.simplejs.host.ScriptHeapGuard;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.values.JsValue;

final class RunLifecycle {
    private final Interpreter interpreter;

    RunLifecycle(Interpreter interpreter) {
        this.interpreter = interpreter;
    }

    private static final long DEADLINE_CHECK_MASK = 0x3F;
    private static final long HEAP_CHECK_MASK = 0x3FFF;
    private static final long LARGE_CHARGE_BYTES = 65_536L;

    void tick() {
        if (interpreter.instructionsRemaining >= 0) {
            if (interpreter.instructionsRemaining == 0) {
                throw new ScriptLimitException("Script exceeded its instruction budget");
            }
            interpreter.instructionsRemaining--;
        }
        interpreter.instructionsUsed++;
        if (interpreter.cancellation != null && interpreter.cancellation.isCancelled()) {
            throw new ScriptCancelledException("Script was cancelled");
        }
        if ((interpreter.instructionsUsed & DEADLINE_CHECK_MASK) == 0) {
            checkDeadline();
            if ((interpreter.instructionsUsed & HEAP_CHECK_MASK) == 0) {
                ScriptHeapGuard.check(0L);
            }
        }
    }

    void tick(long weight) {
        if (interpreter.instructionsRemaining >= 0) {
            if (interpreter.instructionsRemaining < weight) {
                interpreter.instructionsRemaining = 0;
                throw new ScriptLimitException("Script exceeded its instruction budget");
            }
            interpreter.instructionsRemaining -= weight;
        }
        interpreter.instructionsUsed += weight;
        if (interpreter.cancellation != null && interpreter.cancellation.isCancelled()) {
            throw new ScriptCancelledException("Script was cancelled");
        }
        checkDeadline();
    }

    void checkDeadline() {
        if (interpreter.deadlineNanos >= 0 && System.nanoTime() >= interpreter.deadlineNanos) {
            throw new ScriptTimeoutException(TIMED_OUT_MESSAGE);
        }
    }

    void charge(long bytes) {
        if (bytes >= LARGE_CHARGE_BYTES) {
            ScriptHeapGuard.check(bytes);
        }
        if (interpreter.bytesRemaining < 0 || bytes <= 0) {
            return;
        }
        if (bytes > interpreter.bytesRemaining) {
            throw new ScriptMemoryException("Script exceeded its memory budget");
        }
        interpreter.bytesRemaining -= bytes;
        interpreter.peakBytesUsed = Math.max(interpreter.peakBytesUsed,
                interpreter.memoryBudget - interpreter.bytesRemaining);
    }

    void noteAllocation(long bytes) {
        if (bytes >= LARGE_CHARGE_BYTES) {
            ScriptHeapGuard.check(bytes);
        }
    }

    void release(long bytes) {
        if (interpreter.bytesRemaining < 0 || bytes <= 0) {
            return;
        }
        interpreter.bytesRemaining = Math.min(interpreter.memoryBudget, interpreter.bytesRemaining + bytes);
    }

    JsValue evalProgram(Program program) {
        return interpreter.runModule(program, (outcome, ignored) -> outcome).lastValue();
    }

    private static void cancelQuietly(Coroutine pending) {
        try {
            pending.cancel();
        } catch (RuntimeException ignored) {
        }
    }

    void cancelPendingCoroutines() {
        for (final var pending : interpreter.coroutines) {
            if (!pending.isDone()) {
                cancelQuietly(pending);
            }
        }
    }

}
