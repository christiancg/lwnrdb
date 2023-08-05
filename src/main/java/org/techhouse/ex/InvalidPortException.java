package org.techhouse.ex;

public class InvalidPortException extends RuntimeException {
    public InvalidPortException(String invalidPort, Throwable exception) {
        super("Invalid port: " + invalidPort, exception);
    }
}
