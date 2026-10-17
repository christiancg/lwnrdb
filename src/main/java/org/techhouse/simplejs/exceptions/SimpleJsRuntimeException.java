package org.techhouse.simplejs.exceptions;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class SimpleJsRuntimeException extends RuntimeException {
    private final transient AtomicReference<List<String>> capturedStack = new AtomicReference<>();

    public SimpleJsRuntimeException(String message) {
        super(message, null, false, false);
    }

    public boolean hasCapturedStack() {
        return capturedStack.get() != null;
    }

    public void stampIfAbsent(List<String> frames) {
        capturedStack.compareAndSet(null, frames);
    }

    public List<String> getCapturedStack() {
        final var frames = capturedStack.get();
        return frames == null ? List.of() : frames;
    }
}
