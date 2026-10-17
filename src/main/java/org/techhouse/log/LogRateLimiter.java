package org.techhouse.log;

public final class LogRateLimiter {
    private static final long WINDOW_NANOS = 1_000_000_000L;
    private final int perWindow;
    private long windowStart = System.nanoTime();
    private int admittedInWindow;
    private long suppressed;

    public LogRateLimiter(int perWindow) {
        this.perWindow = perWindow;
    }

    public synchronized String admit(String message) {
        final var now = System.nanoTime();
        if (now - windowStart >= WINDOW_NANOS) {
            windowStart = now;
            admittedInWindow = 0;
        }
        if (admittedInWindow >= perWindow) {
            suppressed++;
            return null;
        }
        admittedInWindow++;
        if (suppressed == 0) {
            return message;
        }
        final var withCount = message + " (" + suppressed + " similar entries suppressed)";
        suppressed = 0;
        return withCount;
    }
}
