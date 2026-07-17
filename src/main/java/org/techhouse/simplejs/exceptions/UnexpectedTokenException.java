package org.techhouse.simplejs.exceptions;

public class UnexpectedTokenException extends RuntimeException {
    public UnexpectedTokenException(String token, int index) {
        super("Unexpected token " + token + " at index: " + index);
    }
}
