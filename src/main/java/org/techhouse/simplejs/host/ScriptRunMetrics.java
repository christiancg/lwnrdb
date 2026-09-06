package org.techhouse.simplejs.host;

import org.techhouse.ejson.elements.JsonObject;

/**
 * What one script run consumed. The budgets travel beside the figures so a caller can read a run as a
 * fraction of its sandbox without having to know the server's configuration.
 */
public record ScriptRunMetrics(long instructions, long instructionBudget, long peakMemoryBytes, long memoryBudget,
        long dbOperations, long durationMs) {

    public static final ScriptRunMetrics EMPTY = new ScriptRunMetrics(0L, -1L, 0L, -1L, 0L, 0L);

    public ScriptRunMetrics withHostCounters(long hostOperations, long elapsedMs) {
        return new ScriptRunMetrics(instructions, instructionBudget, peakMemoryBytes, memoryBudget, hostOperations,
                elapsedMs);
    }

    public JsonObject toJson() {
        final var json = new JsonObject();
        json.addProperty("instructions", instructions);
        json.addProperty("instructionBudget", instructionBudget);
        json.addProperty("peakMemoryBytes", peakMemoryBytes);
        json.addProperty("memoryBudget", memoryBudget);
        json.addProperty("dbOperations", dbOperations);
        json.addProperty("durationMs", durationMs);
        return json;
    }
}
