package org.techhouse.simplejs.host;

public record ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth) {
    public static ResourceLimits unlimited() {
        return new ResourceLimits(-1, -1, -1);
    }
}
