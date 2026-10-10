package org.techhouse.ejson.exceptions;

public class NonFiniteNumberException extends RuntimeException {
    public NonFiniteNumberException(String text) {
        super("The number " + text + " is outside the range a JSON number can represent");
    }
}
