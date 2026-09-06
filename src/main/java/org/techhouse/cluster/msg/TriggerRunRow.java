package org.techhouse.cluster.msg;

/**
 * One recorded trigger run as reported over LIST_TRIGGER_RUNS(_ACK): what it was, which node holds its
 * record, how many attempts it has had and why the last one failed.
 */
public class TriggerRunRow {
    private String runId;
    private String status;
    private String database;
    private String collection;
    private String triggerName;
    private String procedureName;
    private String eventType;
    private int attempts;
    private String lastError;
    private long firedAt;
    private long nextAttemptAt;

    // EJson's ReflectionUtils.createInstance looks for a public no-arg constructor before anything else,
    // so this is what deserializes a LIST_TRIGGER_RUNS_ACK; without it the wire class would fall through
    // to UnsafeAllocator. Called only reflectively, hence the suppression.
    @SuppressWarnings("unused")
    public TriggerRunRow() {
    }

    public TriggerRunRow(String runId, String status, String database, String collection, String triggerName,
            String procedureName, String eventType, int attempts, String lastError, long firedAt, long nextAttemptAt) {
        this.runId = runId;
        this.status = status;
        this.database = database;
        this.collection = collection;
        this.triggerName = triggerName;
        this.procedureName = procedureName;
        this.eventType = eventType;
        this.attempts = attempts;
        this.lastError = lastError;
        this.firedAt = firedAt;
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getRunId() {
        return runId;
    }

    public String getStatus() {
        return status;
    }

    public String getDatabase() {
        return database;
    }

    public String getCollection() {
        return collection;
    }

    public String getTriggerName() {
        return triggerName;
    }

    public String getProcedureName() {
        return procedureName;
    }

    public String getEventType() {
        return eventType;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public long getFiredAt() {
        return firedAt;
    }

    public long getNextAttemptAt() {
        return nextAttemptAt;
    }
}
