package org.techhouse.simplejs.host;

import java.util.List;

// strictScriptGoal picks the parse goal: false (the host default) keeps the relaxed contract a stored
// script is written against - a top-level `return`, `import`/`export`, `import.meta` - while true
// raises the ECMAScript Script goal's early errors instead.
public record ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth,
        boolean reportUnhandledRejections, boolean fetchEnabled, List<String> fetchHostAllowlist, long maxResponseBytes,
        long fetchTimeoutMillis, boolean strictScriptGoal, boolean textImportEnabled, int maxModuleDepth,
        int maxLogLines, int maxLogLineChars) {

    // Not the cycle mechanism (the module registry detects cycles); a bound on genuine Java recursion,
    // since each nested module evaluation nests the interpreter's own stack.
    public static final int DEFAULT_MAX_MODULE_DEPTH = 16;
    public static final int DEFAULT_MAX_LOG_LINES = 1000;
    public static final int DEFAULT_MAX_LOG_LINE_CHARS = 4096;

    public ResourceLimits {
        fetchHostAllowlist = fetchHostAllowlist == null ? List.of() : List.copyOf(fetchHostAllowlist);
    }

    public ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth, boolean reportUnhandledRejections,
            boolean fetchEnabled, List<String> fetchHostAllowlist, long maxResponseBytes, long fetchTimeoutMillis,
            boolean strictScriptGoal, boolean textImportEnabled, int maxModuleDepth) {
        this(instructionBudget, wallClockMillis, maxDepth, reportUnhandledRejections, fetchEnabled, fetchHostAllowlist,
                maxResponseBytes, fetchTimeoutMillis, strictScriptGoal, textImportEnabled, maxModuleDepth,
                DEFAULT_MAX_LOG_LINES, DEFAULT_MAX_LOG_LINE_CHARS);
    }

    public ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth, boolean reportUnhandledRejections,
            boolean fetchEnabled, List<String> fetchHostAllowlist, long maxResponseBytes, long fetchTimeoutMillis,
            boolean strictScriptGoal) {
        this(instructionBudget, wallClockMillis, maxDepth, reportUnhandledRejections, fetchEnabled, fetchHostAllowlist,
                maxResponseBytes, fetchTimeoutMillis, strictScriptGoal, false, DEFAULT_MAX_MODULE_DEPTH);
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

    // An unlimited compute budget still caps logs: an unbounded buffer is a heap risk regardless.
    public static ResourceLimits unlimited() {
        return new ResourceLimits(-1, -1, -1, true);
    }
}
