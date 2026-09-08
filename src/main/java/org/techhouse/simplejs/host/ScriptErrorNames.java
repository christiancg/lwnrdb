package org.techhouse.simplejs.host;

public final class ScriptErrorNames {
    public static final String CANCELLED = "ScriptCancelledError";
    public static final String TIMEOUT = "ScriptTimeoutError";
    public static final String MEMORY = "ScriptMemoryError";
    public static final String LIMIT = "ScriptLimitError";
    public static final String RESULT_TOO_LARGE = "ScriptResultTooLargeError";
    public static final String PENDING_RESULT = "ScriptPendingResultError";

    public static final String EXHAUSTED_MEMORY_MESSAGE = "Script exhausted available memory";
    public static final String TIMED_OUT_MESSAGE = "Script exceeded its time limit";

    private ScriptErrorNames() {
    }
}
