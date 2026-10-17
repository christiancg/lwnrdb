package org.techhouse.ex;

public class InvalidPathSegmentException extends RuntimeException {
    public InvalidPathSegmentException() {
        super("A name cannot be used as a path segment");
    }
}
