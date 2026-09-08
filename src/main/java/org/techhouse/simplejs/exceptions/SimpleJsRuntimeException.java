package org.techhouse.simplejs.exceptions;

import java.util.List;
import org.techhouse.simplejs.internal.interpreter.StackCapture;

public class SimpleJsRuntimeException extends RuntimeException {
    private final transient List<String> capturedStack;

    public SimpleJsRuntimeException(String message) {
        super(message);
        capturedStack = StackCapture.current();
    }

    public List<String> getCapturedStack() {
        return capturedStack;
    }
}
