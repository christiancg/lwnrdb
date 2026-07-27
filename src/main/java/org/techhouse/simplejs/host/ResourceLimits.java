package org.techhouse.simplejs.host;

import java.util.List;

public record ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth,
        boolean reportUnhandledRejections, boolean fetchEnabled, List<String> fetchHostAllowlist, long maxResponseBytes,
        long fetchTimeoutMillis) {

    public ResourceLimits {
        fetchHostAllowlist = fetchHostAllowlist == null ? List.of() : List.copyOf(fetchHostAllowlist);
    }

    public ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth,
            boolean reportUnhandledRejections) {
        this(instructionBudget, wallClockMillis, maxDepth, reportUnhandledRejections, false, List.of(), -1, -1);
    }

    public ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth) {
        this(instructionBudget, wallClockMillis, maxDepth, true);
    }

    public static ResourceLimits unlimited() {
        return new ResourceLimits(-1, -1, -1, true);
    }
}
