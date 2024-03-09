package org.techhouse.ejson2.exceptions;

public class MissingEndOfStringException extends RuntimeException {
    public MissingEndOfStringException() {
        super("Missing end of string on json");
    }
}
