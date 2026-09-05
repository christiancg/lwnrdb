package org.techhouse.simplejs.exceptions;

import java.util.List;
import org.techhouse.simplejs.internal.interpreter.StackCapture;

public class SimpleJsRuntimeException extends RuntimeException {
    // Taken here rather than where the exception is turned into a JS error object: by then every
    // interpreter frame has been unwound by the Java stack it rode out on.
    private final transient List<String> capturedStack;

    public SimpleJsRuntimeException(String message) {
        super(message);
        capturedStack = StackCapture.current();
    }

    public List<String> getCapturedStack() {
        return capturedStack;
    }
}
