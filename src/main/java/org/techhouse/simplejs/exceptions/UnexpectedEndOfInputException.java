package org.techhouse.simplejs.exceptions;

public class UnexpectedEndOfInputException extends RuntimeException {
    public UnexpectedEndOfInputException() {
        super("Unexpected end of input");
    }
}
