package org.techhouse.ex;

public class InvalidCronException extends RuntimeException {
    public InvalidCronException(String expression, String reason) {
        super("Invalid cron expression '" + expression + "': " + reason);
    }
}
