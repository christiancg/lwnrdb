package org.techhouse.simplejs.internal;

import static org.techhouse.simplejs.host.ScriptErrorNames.TIMED_OUT_MESSAGE;

import org.techhouse.simplejs.exceptions.ScriptCancelledException;
import org.techhouse.simplejs.exceptions.ScriptLimitException;
import org.techhouse.simplejs.exceptions.ScriptMemoryException;
import org.techhouse.simplejs.exceptions.ScriptTimeoutException;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.values.JsValue;

final class RunLifecycle {
    private final Interpreter interpreter;

    RunLifecycle(Interpreter interpreter) {
        this.interpreter = interpreter;
    }

    void tick() {
        if (interpreter.instructionsRemaining >= 0) {
            if (interpreter.instructionsRemaining == 0) {
                throw new ScriptLimitException("Script exceeded its instruction budget");
            }
            interpreter.instructionsRemaining--;
        }
        interpreter.instructionsUsed++;
        if (interpreter.deadlineNanos >= 0 && System.nanoTime() >= interpreter.deadlineNanos) {
            throw new ScriptTimeoutException(TIMED_OUT_MESSAGE);
        }
        if (interpreter.cancellation != null && interpreter.cancellation.isCancelled()) {
            throw new ScriptCancelledException("Script was cancelled");
        }
    }

    void charge(long bytes) {
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

    void release(long bytes) {
        if (interpreter.bytesRemaining < 0 || bytes <= 0) {
            return;
        }
        interpreter.bytesRemaining = Math.min(interpreter.memoryBudget, interpreter.bytesRemaining + bytes);
    }

    JsValue evalProgram(Program program) {
        return interpreter.runModule(program, (outcome, ignored) -> outcome).lastValue();
    }

    void cancelPendingCoroutines() {
        for (final var pending : interpreter.coroutines) {
            if (!pending.isDone()) {
                pending.cancel();
            }
        }
    }

}
