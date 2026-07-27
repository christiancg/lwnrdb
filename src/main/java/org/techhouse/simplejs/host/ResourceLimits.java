package org.techhouse.simplejs.host;

public record ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth,
        boolean reportUnhandledRejections) {
    public ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth) {
        this(instructionBudget, wallClockMillis, maxDepth, true);
    }

    public static ResourceLimits unlimited() {
        return new ResourceLimits(-1, -1, -1, true);
    }
}
