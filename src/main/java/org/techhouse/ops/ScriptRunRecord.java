package org.techhouse.ops;

import java.util.List;
import org.techhouse.simplejs.host.ScriptRunMetrics;

/**
 * One finished script run, on its way to the history collection of the database it ran against.
 *
 * <p>
 * {@code outcome} is the coarse verdict an operator filters on - {@code ok}, {@code error},
 * {@code skipped}, {@code dead_letter}, {@code reject} - while {@code errorName}/{@code errorMessage}
 * carry the detail. {@code procedure}, {@code collection} and {@code event} are only meaningful for the
 * kinds that have them and are null otherwise.
 */
public record ScriptRunRecord(String runId, ScriptRunKind kind, String database, String name, String procedure,
        String collection, String event, String username, String actingUser, long startedAt, long durationMs,
        int attempt, String outcome, String errorName, String errorMessage, List<String> stack,
        ScriptRunMetrics metrics, List<String> logs, boolean logsTruncated) {

    public static final String OUTCOME_OK = "ok";
    public static final String OUTCOME_ERROR = "error";
    public static final String OUTCOME_SKIPPED = "skipped";
    public static final String OUTCOME_DEAD_LETTER = "dead_letter";

    public ScriptRunRecord {
        stack = stack == null ? List.of() : List.copyOf(stack);
        logs = logs == null ? List.of() : List.copyOf(logs);
        metrics = metrics == null ? ScriptRunMetrics.EMPTY : metrics;
    }
}
