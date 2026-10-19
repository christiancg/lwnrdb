package org.techhouse.client;

public class ClientException extends RuntimeException {
    public static final int OK = 0;
    public static final int FAILED = 1;
    public static final int USAGE = 2;
    public static final int UNREACHABLE = 3;

    private final int exitCode;

    public ClientException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public ClientException(String message, int exitCode, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
