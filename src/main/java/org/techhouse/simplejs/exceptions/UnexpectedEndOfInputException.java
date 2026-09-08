package org.techhouse.simplejs.exceptions;

public class UnexpectedEndOfInputException extends RuntimeException {
    public UnexpectedEndOfInputException() {
        super("Unexpected end of input");
    }

    public UnexpectedEndOfInputException(int line, int column) {
        super("Unexpected end of input at line: " + line + ", column: " + column);
    }
}
