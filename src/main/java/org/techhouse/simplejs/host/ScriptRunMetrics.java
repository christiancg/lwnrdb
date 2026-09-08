package org.techhouse.simplejs.host;

import org.techhouse.ejson.elements.JsonObject;

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
