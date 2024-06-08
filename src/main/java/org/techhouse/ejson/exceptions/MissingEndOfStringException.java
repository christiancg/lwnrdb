package org.techhouse.ejson.exceptions;

public class MissingEndOfStringException extends RuntimeException {
    public MissingEndOfStringException() {
        super("Missing end of string on json");
    }
}
