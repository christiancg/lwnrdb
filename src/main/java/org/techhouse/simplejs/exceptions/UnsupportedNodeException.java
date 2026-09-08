package org.techhouse.simplejs.exceptions;

public class UnsupportedNodeException extends SimpleJsRuntimeException {
    public UnsupportedNodeException(String nodeType) {
        super("Unsupported node in this interpreter phase: " + nodeType);
    }
}
