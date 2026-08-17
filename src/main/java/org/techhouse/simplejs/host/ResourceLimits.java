package org.techhouse.simplejs.host;

import java.util.List;

// strictScriptGoal picks the parse goal: false (the host default) keeps the relaxed contract a stored
// script is written against - a top-level `return`, `import`/`export`, `import.meta` - while true
// raises the ECMAScript Script goal's early errors instead.
public record ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth,
        boolean reportUnhandledRejections, boolean fetchEnabled, List<String> fetchHostAllowlist, long maxResponseBytes,
        long fetchTimeoutMillis, boolean strictScriptGoal) {

    public ResourceLimits {
        fetchHostAllowlist = fetchHostAllowlist == null ? List.of() : List.copyOf(fetchHostAllowlist);
    }

    public ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth, boolean reportUnhandledRejections,
            boolean fetchEnabled, List<String> fetchHostAllowlist, long maxResponseBytes, long fetchTimeoutMillis) {
        this(instructionBudget, wallClockMillis, maxDepth, reportUnhandledRejections, fetchEnabled, fetchHostAllowlist,
                maxResponseBytes, fetchTimeoutMillis, false);
    }

    public ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth,
            boolean reportUnhandledRejections) {
        this(instructionBudget, wallClockMillis, maxDepth, reportUnhandledRejections, false, List.of(), -1, -1, false);
    }

    public ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth, boolean reportUnhandledRejections,
            boolean strictScriptGoal) {
        this(instructionBudget, wallClockMillis, maxDepth, reportUnhandledRejections, false, List.of(), -1, -1,
                strictScriptGoal);
    }

    public ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth) {
        this(instructionBudget, wallClockMillis, maxDepth, true);
    }

    public static ResourceLimits unlimited() {
        return new ResourceLimits(-1, -1, -1, true);
    }
}
