package org.techhouse.simplejs.host;

import java.util.List;

public record ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth,
        boolean reportUnhandledRejections, boolean fetchEnabled, List<String> fetchHostAllowlist, long maxResponseBytes,
        long fetchTimeoutMillis, boolean strictScriptGoal, boolean textImportEnabled, int maxModuleDepth,
        int maxLogLines, int maxLogLineChars, long memoryBudget, long maxResultBytes, int cursorBatchSize,
        int cursorMaxBatchSize) {
    public static final int DEFAULT_MAX_MODULE_DEPTH = 16;
    public static final int DEFAULT_MAX_LOG_LINES = 1000;
    public static final int DEFAULT_MAX_LOG_LINE_CHARS = 4096;
    public static final int DEFAULT_CURSOR_BATCH_SIZE = 500;
    public static final int DEFAULT_CURSOR_MAX_BATCH_SIZE = 5000;

    public ResourceLimits {
        fetchHostAllowlist = fetchHostAllowlist == null ? List.of() : List.copyOf(fetchHostAllowlist);
    }

    public ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth, boolean reportUnhandledRejections,
            boolean fetchEnabled, List<String> fetchHostAllowlist, long maxResponseBytes, long fetchTimeoutMillis,
            boolean strictScriptGoal, boolean textImportEnabled, int maxModuleDepth, int maxLogLines,
            int maxLogLineChars) {
        this(instructionBudget, wallClockMillis, maxDepth, reportUnhandledRejections, fetchEnabled, fetchHostAllowlist,
                maxResponseBytes, fetchTimeoutMillis, strictScriptGoal, textImportEnabled, maxModuleDepth, maxLogLines,
                maxLogLineChars, -1);
    }

    public ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth, boolean reportUnhandledRejections,
            boolean fetchEnabled, List<String> fetchHostAllowlist, long maxResponseBytes, long fetchTimeoutMillis,
            boolean strictScriptGoal, boolean textImportEnabled, int maxModuleDepth, int maxLogLines,
            int maxLogLineChars, long memoryBudget) {
        this(instructionBudget, wallClockMillis, maxDepth, reportUnhandledRejections, fetchEnabled, fetchHostAllowlist,
                maxResponseBytes, fetchTimeoutMillis, strictScriptGoal, textImportEnabled, maxModuleDepth, maxLogLines,
                maxLogLineChars, memoryBudget, -1, -1, -1);
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

    public ResourceLimits(long instructionBudget, long wallClockMillis, int maxDepth, long memoryBudget) {
        this(instructionBudget, wallClockMillis, maxDepth, true, false, List.of(), -1, -1, false, false,
                DEFAULT_MAX_MODULE_DEPTH, DEFAULT_MAX_LOG_LINES, DEFAULT_MAX_LOG_LINE_CHARS, memoryBudget);
    }

    public static ResourceLimits unlimited() {
        return new ResourceLimits(-1, -1, -1, true);
    }
}
