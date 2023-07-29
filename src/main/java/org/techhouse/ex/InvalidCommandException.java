package org.techhouse.ex;

public class InvalidCommandException extends RuntimeException {
    public InvalidCommandException(Exception exception) {
        super("The command is not valid", exception);
    }
}
